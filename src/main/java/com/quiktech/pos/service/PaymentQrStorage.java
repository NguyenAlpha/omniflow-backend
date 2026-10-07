package com.quiktech.pos.service;

import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Service
public class PaymentQrStorage {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final Path directory;

    public PaymentQrStorage(@Value("${PAYMENT_QR_STORAGE_PATH:./storage/payment-account-qr}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    // Decode and re-encode instead of trusting the filename or MIME supplied by the browser.
    public String upload(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("QR image must be between 1 byte and 5 MB");
        }
        byte[] png;
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException("Upload a valid PNG or JPEG image");
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) {
                    throw new IllegalArgumentException("Upload a PNG or JPEG image");
                }
                reader.setInput(stream);
                if (reader.getWidth(0) > 4096 || reader.getHeight(0) > 4096) {
                    throw new IllegalArgumentException("QR image dimensions must not exceed 4096 x 4096");
                }
                var output = new ByteArrayOutputStream();
                ImageIO.write(reader.read(0), "png", output);
                png = output.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not decode QR image", ex);
        }
        if (png.length > MAX_BYTES) throw new IllegalArgumentException("Decoded QR image exceeds 5 MB; use a smaller image");
        Files.createDirectories(directory);
        String key = UUID.randomUUID() + ".png";
        Files.write(directory.resolve(key), png, StandardOpenOption.CREATE_NEW);
        return key;
    }

    public void requireUploaded(String key) {
        if (key != null && (!validKey(key) || !Files.isRegularFile(directory.resolve(key)))) {
            throw new IllegalArgumentException("QR image does not exist; upload it again");
        }
    }

    public ResponseEntity<Resource> image(String key) {
        if (!validKey(key) || !Files.isRegularFile(directory.resolve(key))) {
            throw new ResourceNotFoundException(ErrorCode.PAYMENT_QR_NOT_FOUND, "QR image not found");
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(directory.resolve(key)));
    }

    public static String adminUrl(String key) {
        return key == null ? null : "/api/admin/payment-accounts/qr/" + key;
    }

    private boolean validKey(String key) {
        return key != null && key.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.png");
    }
}
