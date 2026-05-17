package document_processing.tobias_moreno.document.processing;

import document_processing.tobias_moreno.config.OcrProperties;
import document_processing.tobias_moreno.document.processing.ocr.TesseractOcrService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-style check against a real Tesseract installation. Only runs when
 * APP_OCR_TESSDATA_PATH is set in the environment, since Tess4J needs the native
 * binaries and trained data to be available on the host.
 */
@EnabledIfEnvironmentVariable(named = "APP_OCR_TESSDATA_PATH", matches = ".+")
class TesseractOcrServiceTest {

    @Test
    void extractsTextFromGeneratedImage() throws IOException {
        OcrProperties properties = new OcrProperties();
        properties.setTessdataPath(System.getenv("APP_OCR_TESSDATA_PATH"));
        properties.setLanguage("eng");
        TesseractOcrService service = new TesseractOcrService(properties);

        byte[] png = renderTextToPng("HELLO OCR");

        String text = service.extractText(new ByteArrayInputStream(png));

        assertThat(text).containsIgnoringCase("HELLO");
    }

    static byte[] renderTextToPng(String text) throws IOException {
        BufferedImage image = new BufferedImage(400, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
        g.drawString(text, 20, 50);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
