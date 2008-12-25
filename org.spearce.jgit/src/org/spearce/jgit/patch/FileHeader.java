import static org.spearce.jgit.util.RawParseUtils.decodeNoFallback;
import static org.spearce.jgit.util.RawParseUtils.extractBinaryString;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import org.spearce.jgit.util.RawParseUtils;
import org.spearce.jgit.util.TemporaryBuffer;
	/**
	 * Convert the patch script for this file into a string.
	 * <p>
	 * The default character encoding ({@link Constants#CHARSET}) is assumed for
	 * both the old and new files.
	 *
	 * @return the patch script, as a Unicode string.
	 */
	public String getScriptText() {
		return getScriptText(null, null);
	}

	/**
	 * Convert the patch script for this file into a string.
	 *
	 * @param oldCharset
	 *            hint character set to decode the old lines with.
	 * @param newCharset
	 *            hint character set to decode the new lines with.
	 * @return the patch script, as a Unicode string.
	 */
	public String getScriptText(Charset oldCharset, Charset newCharset) {
		return getScriptText(new Charset[] { oldCharset, newCharset });
	}

	protected String getScriptText(Charset[] charsetGuess) {
		if (getHunks().isEmpty()) {
			// If we have no hunks then we can safely assume the entire
			// patch is a binary style patch, or a meta-data only style
			// patch. Either way the encoding of the headers should be
			// strictly 7-bit US-ASCII and the body is either 7-bit ASCII
			// (due to the base 85 encoding used for a BinaryHunk) or is
			// arbitrary noise we have chosen to ignore and not understand
			// (e.g. the message "Binary files ... differ").
			//
			return extractBinaryString(buf, startOffset, endOffset);
		}

		if (charsetGuess != null && charsetGuess.length != getParentCount() + 1)
			throw new IllegalArgumentException("Expected "
					+ (getParentCount() + 1) + " character encoding guesses");

		if (trySimpleConversion(charsetGuess)) {
			Charset cs = charsetGuess != null ? charsetGuess[0] : null;
			if (cs == null)
				cs = Constants.CHARSET;
			try {
				return decodeNoFallback(cs, buf, startOffset, endOffset);
			} catch (CharacterCodingException cee) {
				// Try the much slower, more-memory intensive version which
				// can handle a character set conversion patch.
			}
		}

		final StringBuilder r = new StringBuilder(endOffset - startOffset);

		// Always treat the headers as US-ASCII; Git file names are encoded
		// in a C style escape if any character has the high-bit set.
		//
		final int hdrEnd = getHunks().get(0).getStartOffset();
		for (int ptr = startOffset; ptr < hdrEnd;) {
			final int eol = Math.min(hdrEnd, nextLF(buf, ptr));
			r.append(extractBinaryString(buf, ptr, eol));
			ptr = eol;
		}

		final String[] files = extractFileLines(charsetGuess);
		final int[] offsets = new int[files.length];
		for (final HunkHeader h : getHunks())
			h.extractFileLines(r, files, offsets);
		return r.toString();
	}

	private static boolean trySimpleConversion(final Charset[] charsetGuess) {
		if (charsetGuess == null)
			return true;
		for (int i = 1; i < charsetGuess.length; i++) {
			if (charsetGuess[i] != charsetGuess[0])
				return false;
		}
		return true;
	}

	private String[] extractFileLines(final Charset[] csGuess) {
		final TemporaryBuffer[] tmp = new TemporaryBuffer[getParentCount() + 1];
		try {
			for (int i = 0; i < tmp.length; i++)
				tmp[i] = new TemporaryBuffer();
			for (final HunkHeader h : getHunks())
				h.extractFileLines(tmp);

			final String[] r = new String[tmp.length];
			for (int i = 0; i < tmp.length; i++) {
				Charset cs = csGuess != null ? csGuess[i] : null;
				if (cs == null)
					cs = Constants.CHARSET;
				r[i] = RawParseUtils.decode(cs, tmp[i].toByteArray());
			}
			return r;
		} catch (IOException ioe) {
			throw new RuntimeException("Cannot convert script to text", ioe);
		} finally {
			for (final TemporaryBuffer b : tmp) {
				if (b != null)
					b.destroy();
			}
		}
	}
