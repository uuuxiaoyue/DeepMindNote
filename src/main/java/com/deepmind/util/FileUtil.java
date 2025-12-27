package com.deepmind.util;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;

public class FileUtil {
    private static final String BASE_DIR = "notes";

    // 确保目录存在
    public static void initStorage() {
        try {
            Files.createDirectories(Paths.get(BASE_DIR));
        } catch (IOException e) {
            System.err.println("无法创建存储目录");
        }
    }

    public static void save(String title, String content) throws IOException {
        Files.writeString(Paths.get(BASE_DIR, title + ".md"), content);
    }

    public static String read(String title) throws IOException {
        return Files.readString(Paths.get(BASE_DIR, title + ".md"));
    }

    public static List<String> listAllNotes() throws IOException {
        return Files.list(Paths.get(BASE_DIR))
                .filter(path -> path.toString().endsWith(".md"))
                .map(path -> path.getFileName().toString().replace(".md", ""))
                .collect(Collectors.toList());
    }

    public static void delete(String title) throws IOException {
        Path filePath = Paths.get(BASE_DIR, title + ".md");
        Files.deleteIfExists(filePath);
    }
}
