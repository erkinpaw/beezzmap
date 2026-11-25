package land.beezz.map.site;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Pictures the game can read: WebP (the site's screenshots), JPEG and the like become PNG. */
public final class Pictures {
    private Pictures() {
    }

    public static byte[] png(byte[] data) throws IOException {
        if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return data;
        }
        BufferedImage img;
        if (data.length > 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' && data[8] == 'W' && data[9] == 'E') {
            img = webp(data);
        } else {
            img = ImageIO.read(new ByteArrayInputStream(data));
        }
        if (img == null) {
            throw new IOException("not a picture Java can read");
        }
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        rgb.getGraphics().drawImage(img, 0, 0, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(rgb, "png", out);
        return out.toByteArray();
    }

    /** The reader is made directly (no ImageIO plugin lookup: the game's class loader doesn't find plugins). */
    private static BufferedImage webp(byte[] data) throws IOException {
        javax.imageio.ImageReader r = new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi().createReaderInstance();
        try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            r.setInput(in);
            return r.read(0);
        } finally {
            r.dispose();
        }
    }
}
