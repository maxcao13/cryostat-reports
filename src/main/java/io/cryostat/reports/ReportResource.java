/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.reports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.cryostat.core.reports.InterruptibleReportGenerator;
import io.cryostat.core.reports.InterruptibleReportGenerator.ReportGenerationEvent;
import io.cryostat.core.reports.InterruptibleReportGenerator.ReportResult;
import io.cryostat.core.reports.InterruptibleReportGenerator.RuleEvaluation;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.core.util.RuleFilterParser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.MultipartForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.openjdk.jmc.common.io.IOToolkit;
import org.openjdk.jmc.flightrecorder.rules.IRule;

@Path("/")
public class ReportResource {

    private static final String SINGLETHREAD_PROPERTY =
            "org.openjdk.jmc.flightrecorder.parser.singlethreaded";

    @ConfigProperty(name = "io.cryostat.reports.memory-factor", defaultValue = "10")
    String memoryFactor;

    @ConfigProperty(name = "io.cryostat.reports.timeout", defaultValue = "29000")
    String timeoutMs;

    @Inject Logger logger;
    @Inject InterruptibleReportGenerator generator;
    @Inject FileSystem fs;

    RuleFilterParser rfp = new RuleFilterParser();

    void onStart(@Observes StartupEvent ev) {
        logger.infof(
                "CPUs: %d singlethread: %b maxMemory: %dM memoryFactor: %s timeout: %sms",
                Runtime.getRuntime().availableProcessors(),
                Boolean.getBoolean(SINGLETHREAD_PROPERTY),
                Runtime.getRuntime().maxMemory() / (1024 * 1024),
                memoryFactor,
                timeoutMs);
    }

    @Path("health")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public void healthCheck() {}

    @Blocking
    @Path("report")
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @POST
    public String getReport(RoutingContext ctx, @MultipartForm RecordingFormData form)
            throws IOException {
        FileUpload upload = form.file;

        Triple<java.nio.file.Path, ReportGenerationEvent, Pair<Long, Long>> tripleHelper =
                reportHelper(upload);
        java.nio.file.Path file = tripleHelper.getLeft();
        ReportGenerationEvent evt = tripleHelper.getMiddle();
        long timeout = TimeUnit.MILLISECONDS.toNanos(Long.parseLong(timeoutMs));
        long start = tripleHelper.getRight().getLeft();
        long elapsed = tripleHelper.getRight().getRight();

        Predicate<IRule> predicate = rfp.parse(form.filter);
        Future<ReportResult> reportFuture = null;

        try (var stream = fs.newInputStream(file)) {
            reportFuture = generator.generateReportInterruptibly(stream, predicate);
            ctxHelper(ctx, reportFuture);
            evt.setRecordingSizeBytes(reportFuture.get().getReportStats().getRecordingSizeBytes());
            evt.setRulesEvaluated(reportFuture.get().getReportStats().getRulesEvaluated());
            evt.setRulesApplicable(reportFuture.get().getReportStats().getRulesApplicable());
            return reportFuture.get(timeout - elapsed, TimeUnit.NANOSECONDS).getHtml();
        } catch (ExecutionException | InterruptedException e) {
            throw new InternalServerErrorException(e);
        } catch (TimeoutException e) {
            throw new ServerErrorException(Response.Status.GATEWAY_TIMEOUT, e);
        } finally {
            cleanupHelper(reportFuture, file, evt, upload.fileName(), start);
        }
    }

    @Blocking
    @Path("report")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @POST
    public String getEval(RoutingContext ctx, @MultipartForm RecordingFormData form)
            throws IOException {
        FileUpload upload = form.file;

        Triple<java.nio.file.Path, ReportGenerationEvent, Pair<Long, Long>> tripleHelper =
                reportHelper(upload);
        java.nio.file.Path file = tripleHelper.getLeft();
        ReportGenerationEvent evt = tripleHelper.getMiddle();
        long timeout = TimeUnit.MILLISECONDS.toNanos(Long.parseLong(timeoutMs));
        long start = tripleHelper.getRight().getLeft();
        long elapsed = tripleHelper.getRight().getRight();

        Predicate<IRule> predicate = rfp.parse(form.filter);
        Future<Map<String, RuleEvaluation>> evalMapFuture = null;

        ObjectMapper oMapper = new ObjectMapper();
        try (var stream = fs.newInputStream(file)) {
            evalMapFuture = generator.generateEvalMapInterruptibly(stream, predicate);
            ctxHelper(ctx, evalMapFuture);
            var evalStats = getEvalStats(evalMapFuture.get());
            evt.setRecordingSizeBytes(evalStats.getLeft());
            evt.setRulesEvaluated(evalStats.getMiddle());
            evt.setRulesApplicable(evalStats.getRight());
            return oMapper.writeValueAsString(
                    evalMapFuture.get(timeout - elapsed, TimeUnit.NANOSECONDS));
        } catch (ExecutionException | InterruptedException e) {
            throw new InternalServerErrorException(e);
        } catch (TimeoutException e) {
            throw new ServerErrorException(Response.Status.GATEWAY_TIMEOUT, e);
        } finally {
            cleanupHelper(evalMapFuture, file, evt, upload.fileName(), start);
        }
    }

    private Triple<Long, Integer, Integer> getEvalStats(Map<String, RuleEvaluation> evalMap) {
        // TODO: Add some sort of ReportStats for EvalMap/RuleEvaluation (setRecordingSizeBytes)
        int rulesEvaluated = evalMap.size();
        int rulesApplicable =
                (int) evalMap.values().stream().filter(result -> result.getScore() >= 0).count();

        return Triple.of(Long.valueOf(0), rulesEvaluated, rulesApplicable);
    }

    private Triple<java.nio.file.Path, ReportGenerationEvent, Pair<Long, Long>> reportHelper(
            FileUpload upload) throws IOException {
        java.nio.file.Path file = upload.uploadedFile();
        long timeout = TimeUnit.MILLISECONDS.toNanos(Long.parseLong(timeoutMs));
        long start = System.nanoTime();
        long now = start;
        long elapsed = 0;
        ReportGenerationEvent evt = new ReportGenerationEvent(upload.fileName());

        logger.infof("Received request for %s (%d bytes)", upload.fileName(), upload.size());
        evt.begin();

        if (IOToolkit.isCompressedFile(file.toFile())) {
            file = decompress(file);
            now = System.nanoTime();
            elapsed = now - start;
            logger.infof(
                    "%s was compressed. Decompressed size: %d bytes. Decompression took %dms",
                    upload.fileName(),
                    file.toFile().length(),
                    TimeUnit.NANOSECONDS.toMillis(elapsed));
        }

        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long availableMemory = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
        long maxHandleableSize = availableMemory / Long.parseLong(memoryFactor);
        if (file.toFile().length() > maxHandleableSize) {
            throw new ClientErrorException(Response.Status.REQUEST_ENTITY_TOO_LARGE);
        }

        now = System.nanoTime();
        elapsed = now - start;
        if (elapsed > timeout) {
            throw new ServerErrorException(Response.Status.GATEWAY_TIMEOUT);
        }
        return Triple.of(file, evt, Pair.of(start, elapsed));
    }

    private void ctxHelper(RoutingContext ctx, Future<?> ff) {
        ctx.response()
                .exceptionHandler(
                        e -> {
                            logger.error(e);
                            ff.cancel(true);
                        });
        ctx.request()
                .exceptionHandler(
                        e -> {
                            logger.error(e);
                            ff.cancel(true);
                        });
        ctx.addEndHandler().onComplete(ar -> ff.cancel(true));
    }

    private void cleanupHelper(
            Future<?> future,
            java.nio.file.Path file,
            ReportGenerationEvent evt,
            String fileName,
            long start)
            throws IOException {
        if (future != null) {
            future.cancel(true);
        }
        boolean deleted = fs.deleteIfExists(file);
        if (deleted) {
            logger.infof("Deleted %s", file);
        } else {
            logger.infof("Failed to delete %s", file);
        }
        logger.infof(
                "Completed request for %s after %dms",
                fileName, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        evt.end();
        if (evt.shouldCommit()) {
            evt.commit();
        }
    }

    private java.nio.file.Path decompress(java.nio.file.Path file) throws IOException {
        java.nio.file.Path tmp = Files.createTempFile(null, null);
        try (var stream = IOToolkit.openUncompressedStream(file.toFile())) {
            fs.copy(stream, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        } finally {
            fs.deleteIfExists(file);
        }
    }
}
