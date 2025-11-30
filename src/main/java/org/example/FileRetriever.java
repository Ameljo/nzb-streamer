package org.example;

import javax.jcr.*;

import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.jackrabbit.core.TransientRepository;
import java.io.IOException;
import java.io.InputStream;

public class FileRetriever {
    public static void main(String[] args) throws RepositoryException {
        Repository repository = JcrUtils.getRepository();
        try {
            Session session = repository.login(new SimpleCredentials("admin", "admin".toCharArray()));
            Node root = session.getRootNode();
            if (root.hasNode("filename.txt")) {
                Node fileNode = root.getNode("filename2.ext");
                if (fileNode.hasNode("jcr:content")) {
                    Node contentNode = fileNode.getNode("jcr:content");
                    if (contentNode.hasProperty("jcr:data")) {
                        Property dataProperty = contentNode.getProperty("jcr:data");
                        try (InputStream is = dataProperty.getBinary().getStream()) {
                            byte[] buffer = new byte[1024];
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                System.out.write(buffer, 0, bytesRead);
                            }
                        }
                    } else {
                        System.out.println("jcr:data property not found.");
                    }
                } else {
                    System.out.println("jcr:content node not found.");
                }
            } else {
                System.out.println("File node not found.");
                dump(root);
            }
            session.logout();
        } catch (RepositoryException | IOException e) {
            e.printStackTrace();
        }
    }

    private static void dump(Node node) throws RepositoryException {
        // First output the node path
        System.out.println(node.getPath());
        // Skip the virtual (and large!) jcr:system subtree
        if (node.getName().equals("jcr:system")) {
            return;
        }

        // Then output the properties
        PropertyIterator properties = node.getProperties();
        while (properties.hasNext()) {
            Property property = properties.nextProperty();
            if (property.getDefinition().isMultiple()) {
                // A multi-valued property, print all values
                Value[] values = property.getValues();
                for (int i = 0; i < values.length; i++) {
                    System.out.println(
                            property.getPath() + " = " + values[i] .getString());
                }
            } else {
                // A single-valued property
                System.out.println(
                        property.getPath() + " = " + property.getString());
            }
        }

        // Finally output all the child nodes recursively
        NodeIterator nodes = node.getNodes();
        while (nodes.hasNext()) {
            dump(nodes.nextNode());
        }
    }
}

