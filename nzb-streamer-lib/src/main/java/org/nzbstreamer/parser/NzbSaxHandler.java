package org.nzbstreamer.parser;

import org.nzbstreamer.model.Head;
import org.nzbstreamer.model.Meta;
import org.nzbstreamer.model.Nzb;
import org.nzbstreamer.model.NzbFile;
import org.nzbstreamer.model.Segment;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.math.BigInteger;

/**
 * Builds an {@link Nzb} from SAX events.
 *
 * <p>{@code head}/{@code meta} and {@code file}/{@code groups}/{@code group}/{@code segments}/
 * {@code segment} are the only elements with any data on them; {@code nzb} itself and the
 * {@code groups}/{@code segments} wrapper elements carry nothing besides their children, so
 * {@link #startElement} and {@link #endElement} do nothing for them.</p>
 */
final class NzbSaxHandler extends DefaultHandler {

    private final Nzb nzb = new Nzb();
    private Head head;
    private Meta currentMeta;
    private NzbFile currentFile;
    private Segment currentSegment;
    private final StringBuilder text = new StringBuilder();

    Nzb result() {
        return nzb;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
        // Cleared on every start, not just the elements this handler cares about: whitespace
        // between sibling tags (indentation, newlines) would otherwise bleed into the next
        // element's captured text.
        text.setLength(0);
        switch (elementName(localName, qName)) {
            case "head" -> head = new Head();
            case "meta" -> {
                currentMeta = new Meta();
                currentMeta.setType(attributes.getValue("type"));
            }
            case "file" -> {
                currentFile = new NzbFile();
                currentFile.setPoster(attributes.getValue("poster"));
                currentFile.setSubject(attributes.getValue("subject"));
                String date = attributes.getValue("date");
                if (date != null && !date.isBlank()) {
                    currentFile.setDate(Long.valueOf(date.trim()));
                }
            }
            case "segment" -> {
                currentSegment = new Segment();
                String bytes = attributes.getValue("bytes");
                if (bytes != null && !bytes.isBlank()) {
                    currentSegment.setBytes(new BigInteger(bytes.trim()));
                }
                String number = attributes.getValue("number");
                if (number != null && !number.isBlank()) {
                    currentSegment.setNumber(new BigInteger(number.trim()));
                }
            }
            default -> {
                // "nzb", "groups", "segments", and anything unknown: nothing to build at the start.
            }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        text.append(ch, start, length);
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        String value = text.toString();
        switch (elementName(localName, qName)) {
            case "head" -> {
                nzb.setHead(head);
                head = null;
            }
            case "meta" -> {
                currentMeta.setValue(value.trim());
                head.getMeta().add(currentMeta);
                currentMeta = null;
            }
            case "file" -> {
                nzb.addFile(currentFile);
                currentFile = null;
            }
            case "group" -> currentFile.getGroups().add(value.trim());
            case "segment" -> {
                currentSegment.setValue(value.trim());
                currentFile.getSegments().add(currentSegment);
                currentSegment = null;
            }
            default -> {
                // "nzb", "groups", "segments": nothing to finish.
            }
        }
        text.setLength(0);
    }

    /** SAX gives an empty localName when the reader is not doing namespace processing. */
    private static String elementName(String localName, String qName) {
        return localName == null || localName.isEmpty() ? qName : localName;
    }
}
