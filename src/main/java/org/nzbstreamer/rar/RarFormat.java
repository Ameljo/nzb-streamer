package org.nzbstreamer.rar;

/**
 * The generation of a RAR container.
 *
 * <p>The two generations have the same first six signature bytes. The blocks after the signature
 * are different. The parser must find the generation before it reads the first block.</p>
 */
public enum RarFormat {

    /** The RAR 1.5 to 4.x format. The signature is {@code 52 61 72 21 1A 07 00}. */
    RAR4,

    /** The RAR 5.0 format and later formats. The signature is {@code 52 61 72 21 1A 07 01 00}. */
    RAR5
}
