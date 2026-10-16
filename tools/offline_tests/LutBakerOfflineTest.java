import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import local.jiege.lut.Rec709LutBaker;

/**
 * Offline parity test: the settings app's Java LUT baker against the Python baker that produced
 * the module's LUTs (tools/bake_master_luts.py). Same sources, byte comparison of the payload.
 * Args: project directory. Floating point differs in the last bits (matrix inverse, pow), so a
 * difference of 1 in a u8 channel is allowed on a tiny fraction of samples; anything else fails.
 */
public final class LutBakerOfflineTest {
    private static final String[][] PAIRS = {
        {"Fuji_NN.cube", "JG_FUJI_NOSTALGIC_NEG.cube.rgb.bin"},
        {"理光正片GR Posi.cube", "JG_RICOH_POSITIVE.cube.rgb.bin"},
        {"理光负片GR Neg.cube", "JG_RICOH_NEGATIVE.cube.rgb.bin"},
        {"Green.cube", "JG_RICOH_CINEMA_GREEN.cube.rgb.bin"},
        {"Yellow.cube", "JG_RICOH_CINEMA_YELLOW.cube.rgb.bin"},
        {"GR-Viv_33_Rec709.cube", "JG_RICOH_VIVID.cube.rgb.bin"},
        {"CineStill-50D.cube", "JG_FILM_CINESTILL_50D.cube.rgb.bin"},
        {"Fuji C200.cube", "JG_FILM_FUJI_C200.cube.rgb.bin"},
        {"HASSELBLAD Blue.cube", "JG_FILM_HASSELBLAD_BLUE.cube.rgb.bin"},
        {"Kodak 5203.cube", "JG_FILM_KODAK_5203.cube.rgb.bin"},
        {"Kodak 5207.cube", "JG_FILM_KODAK_5207.cube.rgb.bin"},
        {"Kodak 5219.cube", "JG_FILM_KODAK_5219.cube.rgb.bin"},
        {"Kodak E200.cube", "JG_FILM_KODAK_E200.cube.rgb.bin"},
        {"Kodak Gold200.cube", "JG_FILM_KODAK_GOLD_200.cube.rgb.bin"},
        {"Portra400.cube", "JG_FILM_KODAK_PORTRA_400.cube.rgb.bin"},
        {"Ultramax 400.cube", "JG_FILM_KODAK_ULTRAMAX_400.cube.rgb.bin"},
    };

    public static void main(String[] args) throws Exception {
        Path project = Paths.get(args[0]);
        byte[] header = Files.readAllBytes(project.resolve("app/src/main/assets/mslut_header.bin"));
        long samples = 0, off1 = 0;
        for (String[] pair : PAIRS) {
            Rec709LutBaker.Cube cube;
            try (InputStreamReader reader = new InputStreamReader(
                    Files.newInputStream(project.resolve("inputs/master_luts").resolve(pair[0])), StandardCharsets.UTF_8)) {
                cube = Rec709LutBaker.parse(reader);
            }
            byte[] java = Rec709LutBaker.bake(cube, header);
            byte[] python = Files.readAllBytes(project.resolve("module/payload/meishe_lut").resolve(pair[1]));
            if (java.length != python.length) fail(pair[1] + ": size " + java.length + " vs " + python.length);
            for (int i = 0; i < Rec709LutBaker.HEADER_SIZE; i++) if (java[i] != python[i]) fail(pair[1] + ": header differs at " + i);
            int fileOff1 = 0;
            for (int i = Rec709LutBaker.HEADER_SIZE; i < java.length; i++) {
                int d = Math.abs((java[i] & 0xff) - (python[i] & 0xff));
                if (d > 1) fail(pair[1] + ": byte " + i + " differs by " + d);
                if (d == 1) fileOff1++;
            }
            samples += Rec709LutBaker.PAYLOAD;
            off1 += fileOff1;
            System.out.println(pair[1] + ": off-by-one " + fileOff1);
        }
        double ratio = off1 / (double) samples;
        if (ratio > 0.001) fail("off-by-one ratio " + ratio);
        System.out.println("PASS: Java baker matches the Python baker (" + off1 + " of " + samples + " bytes off by one)");
    }

    private static void fail(String message) {
        System.out.println("FAIL: " + message);
        System.exit(1);
    }
}
