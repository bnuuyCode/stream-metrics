package io.github.bnuuycode.streammetrics;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The startup signature, printed once the server is actually listening.
 *
 * <p>Printed last on purpose: it ends up at the bottom of the console, which is
 * where the eye lands when startup finishes.
 */
final class Banner {

    /**
     * A private output stream that writes UTF-8 no matter what.
     *
     * <p>This line is the whole trick. On Windows, {@code System.out} uses the
     * console's legacy code page — not UTF-8 — so anything beyond plain ASCII
     * comes out as garbage. Wrapping the raw file descriptor in a PrintStream
     * with an explicit charset bypasses that entirely.
     *
     * <p>Encoding is only half the battle: the terminal font also has to own a
     * glyph for each character. If something renders as an empty box, the
     * encoding is fine and the font simply does not have that symbol.
     */
    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    /**
     * The ESC character that opens every ANSI colour code, built from its
     * character number rather than typed literally.
     *
     * <p>ESC is invisible: typed straight into a string it becomes a byte no
     * editor displays and any tool along the way can quietly mangle. Written
     * this way the source stays plain ASCII and says out loud what it means.
     */
    private static final String ESC = String.valueOf((char) 27);

    // ANSI colour codes. The terminal reads these as "change colour" rather
    // than as text. Supported by the IntelliJ console and Windows Terminal.
    private static final String PINK = ESC + "[38;5;218m";
    private static final String PURPLE = ESC + "[38;5;141m";
    private static final String DIM = ESC + "[2m";
    private static final String RESET = ESC + "[0m";

    private Banner() {
    }

    static void print(int port) {
        OUT.println();
        OUT.println(PINK + "  ✧ made by bnuuy 🐇 in bnuuy code style ૮꒰ ˶• ༝ •˶꒱ა ♡" + RESET);
        OUT.println();
        OUT.println(DIM + "  dashboard" + RESET + "  " + PURPLE + "http://localhost:" + port + RESET);
        OUT.println();
    }
}
