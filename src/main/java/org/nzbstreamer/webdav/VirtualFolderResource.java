package org.nzbstreamer.webdav;

import io.milton.common.StreamUtils;
import io.milton.http.Range;
import io.milton.http.Request;
import io.milton.resource.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class VirtualFolderResource extends AbstractResource implements CollectionResource, GetableResource {

    private static Logger log = LogManager.getLogger(AbstractResource.class);

    private static final String HTML_TEMPLATE = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset='UTF-8'>
            <title>%s</title>
            <style>
                body {
                    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                    background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%);
                    color: #222;
                    margin: 0;
                    padding: 20px;
                    min-height: 100vh;
                }
                .container {
                    max-width: 900px;
                    margin: 0 auto;
                    background: white;
                    border-radius: 12px;
                    box-shadow: 0 10px 40px rgba(0,0,0,0.2);
                    padding: 30px;
                }
                h1 {
                    color: #667eea;
                    margin-top: 0;
                    border-bottom: 2px solid #667eea;
                    padding-bottom: 15px;
                }
                ul {
                    list-style: none;
                    padding: 0;
                }
                li {
                    margin: 12px 0;
                    padding: 10px;
                    border-radius: 6px;
                    transition: background 0.2s;
                }
                li:hover {
                    background: #f5f7ff;
                }
                a {
                    text-decoration: none;
                    color: #336699;
                    font-weight: 500;
                }
                a:hover {
                    text-decoration: underline;
                }
                .icon {
                    margin-right: 12px;
                    font-size: 1.2em;
                }
            </style>
        </head>
        <body>
            <div class='container'>
                <h1>%s</h1>
                %s
            </div>
        </body>
        </html>
        """;

    List<Resource> children = new ArrayList<Resource>();
    private String path;

    public VirtualFolderResource(VirtualFolderResource parent, String name, String path) {
        super(parent, name);
        log.debug("created new folder: " + name);
        this.path = path;
    }

    @Override
    protected Object clone(VirtualFolderResource newParent, String newName) {
        VirtualFolderResource newFolder = new VirtualFolderResource(newParent, newName, this.getPath());
        for (Resource child : parent.getChildren()) {
            AbstractResource res = (AbstractResource) child;
            res.clone(newFolder, child.getName()); // will auto-add to folder
        }
        return newFolder;
    }

    @Override
    public Long getContentLength() {
        long size = 0L;
        for (Resource r : children) {
            if (r instanceof AbstractResource) {
                Long l = ((AbstractResource) r).getContentLength();
                if (l != null) {
                    size += l;
                }
            }
        }
        return null;
    }

    public String getContentType() {
        return null;
    }

    @Override
    public String checkRedirect(Request request) {
        return null;
    }

    @Override
    public List<? extends Resource> getChildren() {
        return children;
    }

    public void setChildren(List<Resource> children) {
        this.children = children;
    }

    static ByteArrayOutputStream readStream(final InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        StreamUtils.readTo(in, bos);
        return bos;
    }

    @Override
    public Resource child(String childName) {
        for (Resource r : getChildren()) {
            if (r.getName().equals(childName)) {
                return r;
            }
        }
        return null;
    }

    @Override
    public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType) {
        try (PrintWriter pw = new PrintWriter(out, true, java.nio.charset.StandardCharsets.UTF_8)) {
            String bodyContent = generateBody();
            String html = String.format(HTML_TEMPLATE, this.getName(), this.path, bodyContent);
            pw.print(html);
            pw.flush();
        }
    }

    protected String generateBody() {
        StringBuilder sb = new StringBuilder();
        sb.append("<ul>");
        for (Resource r : this.children) {
            String resourcePath = this.path + "/" + r.getName();
            String icon = (r instanceof CollectionResource) ? "📁" : "📄";
            sb.append("<li><span class='icon'>")
                    .append(icon)
                    .append("</span><a href='")
                    .append(resourcePath)
                    .append("'>")
                    .append(r.getName())
                    .append("</a></li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @Override
    public String getContentType(String accepts) {
        return "text/html";
    }

}
