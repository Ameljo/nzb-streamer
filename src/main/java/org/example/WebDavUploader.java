package org.example;

import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;

public class WebDavUploader {
    public static void main(String[] args) throws IOException {
        String url = "http://localhost:8080/webdav/default/filename2.ext";
        String username = "admin";
        String password = "admin";
        File file = randomFile();

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPut put = new HttpPut(url);
            put.setEntity(new FileEntity(file));
            put.setHeader("Content-Type", "application/octet-stream");
            put.setHeader("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes()));

            org.apache.http.HttpResponse response = httpClient.execute(put);
            int statusCode = response.getStatusLine().getStatusCode();
            EntityUtils.consume(response.getEntity());
            System.out.println("Upload status: " + statusCode);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public static File randomFile() throws IOException {
        File tempFile = Files.createTempFile("upload_test_", ".bin").toFile();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            Random random = new Random();
            byte[] data = new byte[1024]; // 1KB
            random.nextBytes(data);
            fos.write(data);
        }
        return tempFile;
    }
}