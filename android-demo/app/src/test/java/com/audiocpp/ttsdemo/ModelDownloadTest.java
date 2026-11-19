package com.audiocpp.ttsdemo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.audiocpp.ttsdemo.ModelRepository.Source.HF;
import static com.audiocpp.ttsdemo.ModelRepository.Source.MODELSCOPE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class ModelDownloadTest {
    private static final int PREFIX = 60;
    private static final byte[] MODEL = new byte[128];

    static {
        for (int i = 0; i < MODEL.length; i++) MODEL[i] = (byte) i;
        MODEL[0] = 'G'; MODEL[1] = 'G'; MODEL[2] = 'U'; MODEL[3] = 'F';
    }

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private record Response(int code, Map<String, String> headers, byte[] body) {}

    private static final class Source extends URLStreamHandler {
        final List<String> ranges = new ArrayList<>();
        final List<String> hosts = new ArrayList<>();
        final List<Response> responses;
        int calls;

        Source(Response... responses) { this.responses = List.of(responses); }

        URL url() throws IOException { return new URL(null, "https://origin.test/model", this); }

        @Override protected URLConnection openConnection(URL url) {
            Response response = responses.get(calls++);
            return new HttpURLConnection(url) {
                @Override public int getResponseCode() {
                    if (!connected) {
                        ranges.add(getRequestProperty("Range"));
                        hosts.add(url.getHost());
                        assertEquals("identity", getRequestProperty("Accept-Encoding"));
                        connected = true;
                    }
                    return response.code();
                }
                @Override public String getHeaderField(String name) { return response.headers().get(name); }
                @Override public ByteArrayInputStream getInputStream() { return new ByteArrayInputStream(response.body()); }
                @Override public void connect() {}
                @Override public void disconnect() {}
                @Override public boolean usingProxy() { return false; }
            };
        }
    }

    private static ModelRepository.Entry entry() throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MODEL));
        return new ModelRepository.Entry("family/model.gguf", MODEL.length, hash);
    }

    private static Response full(byte[] body) {
        return new Response(200, Map.of("Content-Length", Integer.toString(MODEL.length)), body);
    }

    private static Response rest(int begin, byte[] bytes) {
        return new Response(206, Map.of("Content-Length", Integer.toString(bytes.length),
                "Content-Range", "bytes " + begin + "-127/128"), bytes);
    }

    private static void download(Source source, ModelRepository.Entry entry, File target) throws IOException {
        ModelRepository.download(source.url(), entry, target, () -> false, (done, total) -> {});
    }

    private File makePartial() throws IOException {
        File target = new File(folder.getRoot(), "model.gguf");
        Files.write(ModelLibrary.downloadPartial(target).toPath(), Arrays.copyOf(MODEL, PREFIX));
        return target;
    }

    @Test public void interruptedDownloadSurvivesRestartAndResumesAcrossSourcesAndRedirect() throws Exception {
        ModelLibrary library = new ModelLibrary(folder.getRoot());
        ModelRepository.Entry entry = entry();
        File target = library.downloadTarget(HF, entry);
        assertThrows(IOException.class,
                () -> download(new Source(full(Arrays.copyOf(MODEL, PREFIX))), entry, target));
        assertFalse(target.exists());
        assertEquals(List.of(), library.files());
        File abandoned = File.createTempFile("gguf-", ".part", target.getParentFile());
        File importPart = new File(library.importTarget("imported.gguf").getParentFile(), "import.part");
        Files.write(importPart.toPath(), MODEL);

        ModelLibrary reopened = new ModelLibrary(folder.getRoot());
        reopened.discardAbandonedTransfers();
        assertFalse(importPart.exists());
        assertFalse(abandoned.exists());
        File mirror = reopened.downloadTarget(MODELSCOPE, entry);
        assertEquals(PREFIX, ModelRepository.resumableBytes(entry, mirror));
        ModelRepository.Entry different = new ModelRepository.Entry(entry.path(), MODEL.length, "f".repeat(64));
        assertNotEquals(mirror, reopened.downloadPath(MODELSCOPE, different));
        assertEquals(0, ModelRepository.resumableBytes(different, reopened.downloadPath(MODELSCOPE, different)));

        Source source = new Source(new Response(302, Map.of("Location", "https://cdn.test/model"), new byte[0]),
                rest(PREFIX, Arrays.copyOfRange(MODEL, PREFIX, MODEL.length)));
        ArrayList<Long> progress = new ArrayList<>();
        ModelRepository.download(source.url(), entry, mirror, () -> false, (done, total) -> progress.add(done));
        assertEquals(List.of("bytes=60-", "bytes=60-"), source.ranges);
        assertEquals(List.of("origin.test", "cdn.test"), source.hosts);
        assertEquals(Long.valueOf(PREFIX), progress.get(0));
        assertArrayEquals(MODEL, Files.readAllBytes(mirror.toPath()));
        assertFalse(ModelLibrary.downloadPartial(mirror).exists());
    }

    @Test public void pauseKeepsPartialAndNeverPublishesIt() throws Exception {
        File target = new File(folder.getRoot(), "model.gguf");
        Source source = new Source(full(MODEL));
        ModelRepository.Entry entry = entry();
        AtomicBoolean paused = new AtomicBoolean();
        assertThrows(CancellationException.class, () -> ModelRepository.download(source.url(), entry, target,
                paused::get, (done, total) -> paused.set(true)));
        assertEquals(24, ModelRepository.resumableBytes(entry, target));
        assertFalse(target.exists());
    }

    @Test public void ignoredRangeReplacesRatherThanAppends() throws Exception {
        File target = makePartial();
        Source source = new Source(full(MODEL));
        download(source, entry(), target);
        assertEquals(List.of("bytes=60-"), source.ranges);
        assertArrayEquals(MODEL, Files.readAllBytes(target.toPath()));
    }

    @Test public void unsatisfiableRangeRetriesWithoutRange() throws Exception {
        File target = makePartial();
        Source source = new Source(new Response(416, Map.of(), new byte[0]), full(MODEL));
        download(source, entry(), target);
        assertEquals(Arrays.asList("bytes=60-", null), source.ranges);
        assertArrayEquals(MODEL, Files.readAllBytes(target.toPath()));
    }

    @Test public void malformedRangeDoesNotChangePartial() throws Exception {
        File target = makePartial();
        ModelRepository.Entry entry = entry();
        assertThrows(IOException.class, () -> download(new Source(rest(PREFIX + 1,
                Arrays.copyOfRange(MODEL, PREFIX + 1, MODEL.length))), entry, target));
        assertEquals(PREFIX, ModelRepository.resumableBytes(entry, target));
        assertFalse(target.exists());
    }

    @Test public void wrongHashDiscardsPartialAndNeverPublishes() throws Exception {
        File target = makePartial();
        ModelRepository.Entry entry = entry();
        byte[] suffix = Arrays.copyOfRange(MODEL, PREFIX, MODEL.length);
        suffix[0]++;
        assertThrows(IOException.class, () -> download(new Source(rest(PREFIX, suffix)), entry, target));
        assertFalse(target.exists());
        assertFalse(ModelLibrary.downloadPartial(target).exists());
    }

    @Test public void fullPartialVerifiesAndPromotesWithoutNetwork() throws Exception {
        File target = makePartial();
        Files.write(ModelLibrary.downloadPartial(target).toPath(), MODEL);
        download(new Source(), entry(), target);
        assertArrayEquals(MODEL, Files.readAllBytes(target.toPath()));
        assertFalse(ModelLibrary.downloadPartial(target).exists());
    }

    @Test public void unknownHashDoesNotPreserveUnverifiedProgress() throws Exception {
        File target = new File(folder.getRoot(), "model.gguf");
        ModelRepository.Entry entry = new ModelRepository.Entry("family/model.gguf", MODEL.length, "");
        assertThrows(IOException.class,
                () -> download(new Source(full(Arrays.copyOf(MODEL, PREFIX))), entry, target));
        assertEquals(0, folder.getRoot().list().length);
    }
}
