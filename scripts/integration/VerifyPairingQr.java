import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Decodes the actual Windows GIF with the Android client's pinned ZXing decoder. */
class VerifyPairingQr {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected fixture GIF and JSON paths");
        BufferedImage image = ImageIO.read(new File(args[0]));
        if (image == null) throw new IllegalStateException("QR image could not be decoded");
        int width = image.getWidth(), height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        // Check the four-module white border used by the shipped Windows renderer.
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if ((x < 24 || y < 24 || x >= width - 24 || y >= height - 24)
                    && (pixels[y * width + x] & 0xFFFFFF) != 0xFFFFFF)
                throw new IllegalStateException("QR quiet zone is not clear");
        }
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
        var result = new MultiFormatReader().decode(bitmap, Map.of(
            DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET, "UTF-8"));
        String expected = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        if (result.getBarcodeFormat() != BarcodeFormat.QR_CODE || !result.getText().equals(expected))
            throw new IllegalStateException("The scanned descriptor differs from the encoded fixture");
        System.out.println("OK: Windows GIF decoded by ZXing; descriptor unchanged; quiet zone verified");
    }
}
