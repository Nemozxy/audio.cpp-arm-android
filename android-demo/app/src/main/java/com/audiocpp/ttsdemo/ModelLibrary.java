package com.audiocpp.ttsdemo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class ModelLibrary {
    static final String PREFERENCES = "MainActivity";
    static final String SELECTED_PATH = "model-path";

    private final File root;

    ModelLibrary(File root) {
        this.root = root;
    }

    List<File> files() {
        List<File> files = new ArrayList<>();
        for (String name : new String[]{"models", "custom-models", "downloaded-models"}) {
            File[] entries = new File(root, name).listFiles();
            if (entries == null) continue;
            for (File entry : entries) {
                if (isModel(entry)) files.add(entry);
                if (entry.isDirectory()) {
                    File[] children = entry.listFiles(ModelLibrary::isModel);
                    if (children != null) {
                        for (File child : children) files.add(child);
                    }
                }
            }
        }
        files.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(File::getPath));
        return files;
    }

    File selected(String path) {
        if (path == null || path.isEmpty()) return null;
        File file = new File(path);
        return files().contains(file) ? file : null;
    }

    String area(File model) {
        File parent = model.getParentFile();
        return parent.getParentFile().equals(root) ? parent.getName() : parent.getParentFile().getName();
    }

    void discardInterruptedTransfers() {
        for (String area : new String[]{"custom-models", "downloaded-models"}) {
            File[] directories = new File(root, area).listFiles(File::isDirectory);
            if (directories == null) continue;
            for (File dir : directories) {
                File[] partials = dir.listFiles(file -> file.getName().endsWith(".part"));
                if (partials != null) {
                    for (File partial : partials) partial.delete();
                }
                dir.delete(); // Only succeeds when empty.
            }
        }
    }

    File importTarget(String displayName) throws IOException {
        String name = displayName.replaceAll("[\\\\/\\p{Cntrl}\\x{10000}-\\x{10ffff}]", "_");
        if (!hasExtension(name)) name += ".gguf";
        if (name.getBytes(StandardCharsets.UTF_8).length > 255) name = "custom.gguf";
        return new File(directory("custom-models", UUID.randomUUID().toString()), name);
    }

    File downloadTarget(ModelRepository.Source source, ModelRepository.Entry entry) throws IOException {
        String id = entry.sha256().isEmpty() ? source.name() + ":" + entry.path() : entry.sha256();
        String key = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
        return new File(directory("downloaded-models", key), entry.name());
    }

    private File directory(String area, String key) throws IOException {
        File dir = new File(new File(root, area), key);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建模型目录");
        return dir;
    }

    boolean remove(File model) {
        if (!files().contains(model) || !model.delete()) return false;
        File dir = model.getParentFile();
        String parent = dir.getParentFile().getName();
        if (parent.equals("custom-models") || parent.equals("downloaded-models")) dir.delete();
        return true;
    }

    static boolean isModel(File file) {
        return file.isFile() && hasExtension(file.getName());
    }

    static boolean hasExtension(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".gguf");
    }

    static boolean readHeader(InputStream input, byte[] header) throws IOException {
        int offset = 0;
        while (offset < header.length) {
            int count = input.read(header, offset, header.length - offset);
            if (count < 0) return false;
            offset += count;
        }
        return header[0] == 'G' && header[1] == 'G' && header[2] == 'U' && header[3] == 'F';
    }
}
