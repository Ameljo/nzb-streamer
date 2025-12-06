package org.transformers;

import org.model.Nzb;

public class NzbToStringTransformer implements NzbTransformer<String> {
    @Override
    public String transform(Nzb nzb) {
        // Simple string representation of the Nzb object
        StringBuilder sb = new StringBuilder();
        sb.append("NZB Files:\n");
        nzb.getFiles().forEach(file -> {
                sb.append("- ").append(file.getSubject()).append("\n");
                sb.append("  Poster: ").append(file.getPoster()).append("\n");
                sb.append("  Date: ").append(file.getDate()).append("\n");
                sb.append("  Segments: ").append(file.getSegments().getSegment().size()).append("\n\n");
        });
        return sb.toString();
    }
}
