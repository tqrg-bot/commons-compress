/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.commons.compress.archivers.arj;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.zip.CRC32;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.utils.BoundedInputStream;
import org.apache.commons.compress.utils.CRC32VerifyingInputStream;

/**
 * Implements the "arj" archive format as an InputStream.
 * <p>
 * <a href="http://farmanager.com/svn/trunk/plugins/multiarc/arc.doc/arj.txt">Reference</a>
 * @NotThreadSafe
 * @since 1.6
 */
public class ArjArchiveInputStream extends ArchiveInputStream {
    private static final int ARJ_MAGIC_1 = 0x60;
    private static final int ARJ_MAGIC_2 = 0xEA;
    private final DataInputStream in;
    private final String charsetName;
    private final MainHeader mainHeader;
    private LocalFileHeader currentLocalFileHeader = null;
    private InputStream currentInputStream = null;
    
    /**
     * Constructs the ArjInputStream, taking ownership of the inputStream that is passed in.
     * @param inputStream the underlying stream, whose ownership is taken
     * @param charsetName the charset used for file names and comments
     *   in the archive
     * @throws ArchiveException
     */
    public ArjArchiveInputStream(final InputStream inputStream,
            final String charsetName) throws ArchiveException {
        in = new DataInputStream(inputStream);
        this.charsetName = charsetName;
        try {
            mainHeader = readMainHeader();
            if ((mainHeader.arjFlags & MainHeader.Flags.GARBLED) != 0) {
                throw new ArchiveException("Encrypted ARJ files are unsupported");
            }
            if ((mainHeader.arjFlags & MainHeader.Flags.VOLUME) != 0) {
                throw new ArchiveException("Multi-volume ARJ files are unsupported");
            }
        } catch (IOException ioException) {
            throw new ArchiveException(ioException.getMessage(), ioException);
        }
    }

    /**
     * Constructs the ArjInputStream, taking ownership of the inputStream that is passed in,
     * and using the CP437 character encoding.
     * @param inputStream the underlying stream, whose ownership is taken
     * @throws ArchiveException
     */
    public ArjArchiveInputStream(final InputStream inputStream)
            throws ArchiveException {
        this(inputStream, "CP437");
    }
    
    @Override
    public void close() throws IOException {
        in.close();
    }

    private int read8(final DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        count(1);
        return value;
    }

    private int read16(final DataInputStream in) throws IOException {
        final int value = in.readUnsignedShort();
        count(2);
        return Integer.reverseBytes(value) >>> 16;
    }

    private int read32(final DataInputStream in) throws IOException {
        final int value = in.readInt();
        count(4);
        return Integer.reverseBytes(value);
    }
    
    private String readString(final DataInputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nextByte;
        while ((nextByte = in.readUnsignedByte()) != 0) {
            buffer.write(nextByte);
        }
        if (charsetName != null) {
            return new String(buffer.toByteArray(), charsetName);
        } else {
            return new String(buffer.toByteArray());
        }
    }
    
    private void readFully(final DataInputStream in, byte[] b)
        throws IOException {
        in.readFully(b);
        count(b.length);
    }
    
    private byte[] readHeader() throws IOException {
        boolean found = false;
        byte[] basicHeaderBytes = null;
        do {
            int first = 0;
            int second = read8(in);
            do {
                first = second;
                second = read8(in);
            } while (first != ARJ_MAGIC_1 && second != ARJ_MAGIC_2);
            final int basicHeaderSize = read16(in);
            if (basicHeaderSize == 0) {
                // end of archive
                return null;
            }
            if (basicHeaderSize <= 2600) {
                basicHeaderBytes = new byte[basicHeaderSize];
                readFully(in, basicHeaderBytes);
                final int basicHeaderCrc32 = read32(in);
                final CRC32 crc32 = new CRC32();
                crc32.update(basicHeaderBytes);
                if (basicHeaderCrc32 == (int)crc32.getValue()) {
                    found = true;
                }
            }
        } while (!found);
        return basicHeaderBytes;
    }
    
    private MainHeader readMainHeader() throws IOException {
        final byte[] basicHeaderBytes = readHeader();
        if (basicHeaderBytes == null) {
            throw new IOException("Archive ends without any headers");
        }
        final DataInputStream basicHeader = new DataInputStream(
                new ByteArrayInputStream(basicHeaderBytes));
        
        final int firstHeaderSize = basicHeader.readUnsignedByte();
        final byte[] firstHeaderBytes = new byte[firstHeaderSize - 1];
        basicHeader.readFully(firstHeaderBytes);
        final DataInputStream firstHeader = new DataInputStream(
                new ByteArrayInputStream(firstHeaderBytes));
        
        final MainHeader mainHeader = new MainHeader();
        mainHeader.archiverVersionNumber = firstHeader.readUnsignedByte();
        mainHeader.minVersionToExtract = firstHeader.readUnsignedByte();
        mainHeader.hostOS = firstHeader.readUnsignedByte();
        mainHeader.arjFlags = firstHeader.readUnsignedByte();
        mainHeader.securityVersion = firstHeader.readUnsignedByte();
        mainHeader.fileType = firstHeader.readUnsignedByte();
        mainHeader.reserved = firstHeader.readUnsignedByte();
        mainHeader.dateTimeCreated = read32(firstHeader);
        mainHeader.dateTimeModified = read32(firstHeader);
        mainHeader.archiveSize = 0xffffFFFFL & read32(firstHeader);
        mainHeader.securityEnvelopeFilePosition = read32(firstHeader);
        mainHeader.fileSpecPosition = read16(firstHeader);
        mainHeader.securityEnvelopeLength = read16(firstHeader);
        pushedBackBytes(20); // count has already counted them via readFully
        mainHeader.encryptionVersion = firstHeader.readUnsignedByte();
        mainHeader.lastChapter = firstHeader.readUnsignedByte();
        
        if (firstHeaderSize >= 33) {
            mainHeader.arjProtectionFactor = firstHeader.readUnsignedByte();
            mainHeader.arjFlags2 = firstHeader.readUnsignedByte();
            firstHeader.readUnsignedByte();
            firstHeader.readUnsignedByte();
        }

        mainHeader.name = readString(basicHeader);
        mainHeader.comment = readString(basicHeader);
        
        final  int extendedHeaderSize = read16(in);
        if (extendedHeaderSize > 0) {
            mainHeader.extendedHeaderBytes = new byte[extendedHeaderSize];
            readFully(in, mainHeader.extendedHeaderBytes);
            final int extendedHeaderCrc32 = read32(in);
            final CRC32 crc32 = new CRC32();
            crc32.update(mainHeader.extendedHeaderBytes);
            if (extendedHeaderCrc32 != (int)crc32.getValue()) {
                throw new IOException("Extended header CRC32 verification failure");
            }
        }
        
        return mainHeader;
    }
    
    private LocalFileHeader readLocalFileHeader() throws IOException {
        final byte[] basicHeaderBytes = readHeader();
        if (basicHeaderBytes == null) {
            return null;
        }
        final DataInputStream basicHeader = new DataInputStream(
                new ByteArrayInputStream(basicHeaderBytes));
        
        final int firstHeaderSize = basicHeader.readUnsignedByte();
        final byte[] firstHeaderBytes = new byte[firstHeaderSize - 1];
        basicHeader.readFully(firstHeaderBytes);
        final DataInputStream firstHeader = new DataInputStream(
                new ByteArrayInputStream(firstHeaderBytes));

        final LocalFileHeader localFileHeader = new LocalFileHeader();
        localFileHeader.archiverVersionNumber = firstHeader.readUnsignedByte();
        localFileHeader.minVersionToExtract = firstHeader.readUnsignedByte();
        localFileHeader.hostOS = firstHeader.readUnsignedByte();
        localFileHeader.arjFlags = firstHeader.readUnsignedByte();
        localFileHeader.method = firstHeader.readUnsignedByte();
        localFileHeader.fileType = firstHeader.readUnsignedByte();
        localFileHeader.reserved = firstHeader.readUnsignedByte();
        localFileHeader.dateTimeModified = read32(firstHeader);
        localFileHeader.compressedSize = 0xffffFFFFL & read32(firstHeader);
        localFileHeader.originalSize = 0xffffFFFFL & read32(firstHeader);
        localFileHeader.originalCrc32 = read32(firstHeader);
        localFileHeader.fileSpecPosition = read16(firstHeader);
        localFileHeader.fileAccessMode = read16(firstHeader);
        pushedBackBytes(20);
        localFileHeader.firstChapter = firstHeader.readUnsignedByte();
        localFileHeader.lastChapter = firstHeader.readUnsignedByte();
        
        readExtraData(firstHeaderSize, firstHeader, localFileHeader);

        localFileHeader.name = readString(basicHeader);
        localFileHeader.comment = readString(basicHeader);

        ArrayList<byte[]> extendedHeaders = new ArrayList<byte[]>();
        int extendedHeaderSize;
        while ((extendedHeaderSize = read16(in)) > 0) {
            final byte[] extendedHeaderBytes = new byte[extendedHeaderSize];
            readFully(in, extendedHeaderBytes);
            final int extendedHeaderCrc32 = read32(in);
            final CRC32 crc32 = new CRC32();
            crc32.update(extendedHeaderBytes);
            if (extendedHeaderCrc32 != (int)crc32.getValue()) {
                throw new IOException("Extended header CRC32 verification failure");
            }
            extendedHeaders.add(extendedHeaderBytes);
        }
        localFileHeader.extendedHeaders = extendedHeaders.toArray(new byte[extendedHeaders.size()][]);
        
        return localFileHeader;
    }
    
    private void readExtraData(int firstHeaderSize, DataInputStream firstHeader,
                               LocalFileHeader localFileHeader) throws IOException {
        if (firstHeaderSize >= 33) {
            localFileHeader.extendedFilePosition = read32(firstHeader);
            if (firstHeaderSize >= 45) {
                localFileHeader.dateTimeAccessed = read32(firstHeader);
                localFileHeader.dateTimeCreated = read32(firstHeader);
                localFileHeader.originalSizeEvenForVolumes = read32(firstHeader);
                pushedBackBytes(12);
            }
            pushedBackBytes(4);
        }
    }

    /**
     * Checks if the signature matches what is expected for an arj file.
     *
     * @param signature
     *            the bytes to check
     * @param length
     *            the number of bytes to check
     * @return true, if this stream is an arj archive stream, false otherwise
     */
    public static boolean matches(final byte[] signature, final int length) {
        return length >= 2 &&
                (0xff & signature[0]) == ARJ_MAGIC_1 &&
                (0xff & signature[1]) == ARJ_MAGIC_2;
    }
    
    /**
     * Gets the archive's recorded name.
     */
    public String getArchiveName() {
        return mainHeader.name;
    }
    
    /**
     * Gets the archive's comment.
     */
    public String getArchiveComment() {
        return mainHeader.comment;
    }
    
    @Override
    public ArjArchiveEntry getNextEntry() throws IOException {
        if (currentInputStream != null) {
            while (currentInputStream.read() >= 0) { // NOPMD
                // drain current input
            }
            currentLocalFileHeader = null;
            currentInputStream = null;
        }
        
        currentLocalFileHeader = readLocalFileHeader();
        if (currentLocalFileHeader != null) {
            currentInputStream = new BoundedInputStream(in, currentLocalFileHeader.compressedSize);
            if (currentLocalFileHeader.method == LocalFileHeader.Methods.STORED) {
                currentInputStream = new CRC32VerifyingInputStream(currentInputStream,
                        currentLocalFileHeader.originalSize, currentLocalFileHeader.originalCrc32);
            }
            return new ArjArchiveEntry(currentLocalFileHeader);
        } else {
            currentInputStream = null;
            return null;
        }
    }
    
    @Override
    public boolean canReadEntryData(ArchiveEntry ae) {
        return currentLocalFileHeader.method == LocalFileHeader.Methods.STORED;
    }
    
    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        if (currentLocalFileHeader.method != LocalFileHeader.Methods.STORED) {
            throw new IOException("Unsupported compression method " + currentLocalFileHeader.method);
        }
        return currentInputStream.read(b, off, len);
    }
}
