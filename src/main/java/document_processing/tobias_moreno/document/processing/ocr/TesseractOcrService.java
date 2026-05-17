package document_processing.tobias_moreno.document.processing.ocr;

import document_processing.tobias_moreno.config.OcrProperties;
import document_processing.tobias_moreno.document.processing.TextExtractionException;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

@Service
public class TesseractOcrService implements OcrService {

    private final OcrProperties properties;

    public TesseractOcrService(OcrProperties properties) {
        this.properties = properties;
    }

    @Override
    public String extractText(InputStream image) {
        BufferedImage bufferedImage;
        try {
            bufferedImage = ImageIO.read(image);
        } catch (IOException e) {
            throw new TextExtractionException("Failed to decode image for OCR", e);
        }
        if (bufferedImage == null) {
            throw new TextExtractionException("Unsupported or corrupt image for OCR");
        }

        ITesseract tesseract = newTesseract();
        try {
            return tesseract.doOCR(bufferedImage);
        } catch (TesseractException e) {
            throw new TextExtractionException("OCR engine failed", e);
        }
    }

    // Visible for tests so a custom ITesseract can be injected if needed.
    protected ITesseract newTesseract() {
        Tesseract tesseract = new Tesseract();
        String tessdataPath = properties.getTessdataPath();
        if (tessdataPath != null && !tessdataPath.isBlank()) {
            tesseract.setDatapath(tessdataPath);
        }
        tesseract.setLanguage(properties.getLanguage());
        return tesseract;
    }
}
