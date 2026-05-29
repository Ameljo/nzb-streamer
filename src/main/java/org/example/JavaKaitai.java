package org.example;

import io.kaitai.struct.ByteBufferKaitaiStream;
import org.katai.Rar5;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class JavaKaitai {

    public static void main(String[] args) throws IOException {
        Path archivePath = Path.of("downloads/test2.rar");
        File archiveFile = archivePath.toFile();
        Rar5 rar5 = new Rar5(new ByteBufferKaitaiStream(Files.readAllBytes(archivePath)));
        rar5.signature();
    }
}
