package tn.hydra.config.demo;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal in-memory {@link MultipartFile} used by the demo scenario to feed
 * tiny generated files (a 2×2 JPEG, a hand-written PDF) to the real services.
 * Production code never uses this; it lives in the {@code demo} package only.
 */
final class InMemoryMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    InMemoryMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content;
    }

    @Override public String getName() { return name; }
    @Override public String getOriginalFilename() { return originalFilename; }
    @Override public String getContentType() { return contentType; }
    @Override public boolean isEmpty() { return content == null || content.length == 0; }
    @Override public long getSize() { return content == null ? 0 : content.length; }
    @Override public byte[] getBytes() { return content == null ? new byte[0] : content; }
    @Override public InputStream getInputStream() {
        return new ByteArrayInputStream(content == null ? new byte[0] : content);
    }
    @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), content); }
    @Override public void transferTo(Path dest) throws IOException { Files.write(dest, content); }
}
