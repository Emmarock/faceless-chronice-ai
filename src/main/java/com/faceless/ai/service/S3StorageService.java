package com.faceless.ai.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@Slf4j
public class S3StorageService {

    private final S3Client s3Client;
    private final String bucket;

    @PostConstruct
    public void init() {
        ensureBucketExists();
    }

    public S3StorageService(
            @Value("${chronicleai.aws.access-key}") String accessKey,
            @Value("${chronicleai.aws.secret-key}") String secretKey,
            @Value("${chronicleai.aws.region}") String region,
            @Value("${chronicleai.aws.s3-bucket}") String bucket,
            @Value("${chronicleai.aws.endpoint-url:}") String endpointUrl) {

        this.bucket = bucket;

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)));

        if (endpointUrl != null && !endpointUrl.isBlank()) {
            // LocalStack (or any S3-compatible store) — force path-style so the bucket
            // name appears in the URL path rather than as a DNS subdomain.
            builder.endpointOverride(URI.create(endpointUrl))
                   .forcePathStyle(true);
            log.info("S3 client using custom endpoint: {}", endpointUrl);
        }

        this.s3Client = builder.build();
    }

    private void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("S3 bucket '{}' already exists.", bucket);
        } catch (NoSuchBucketException e) {
            log.info("S3 bucket '{}' not found — creating it.", bucket);
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("S3 bucket '{}' created.", bucket);
        }
    }

    public boolean exists(String s3Url) {
        String key = s3Url.substring(("s3://" + bucket + "/").length());
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /**
     * Upload a local file to S3 under the given key.
     *
     * @return public S3 URL: s3://{bucket}/{key}
     */
    public String upload(Path localFile, String key) throws IOException {
        log.info("Uploading {} to s3://{}/{}", localFile, bucket, key);
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        s3Client.putObject(req, localFile);
        String url = "s3://" + bucket + "/" + key;
        log.info("Uploaded to {}", url);
        return url;
    }

    /**
     * Best-effort delete for a {@code s3://bucket/key} URL. NoSuchKey is swallowed
     * so this is safe to call when the asset row may already point at a
     * removed object (e.g. retried mutations, LocalStack restarts).
     */
    public void delete(String s3Url) {
        if (s3Url == null || s3Url.isBlank()) return;
        String prefix = "s3://" + bucket + "/";
        if (!s3Url.startsWith(prefix)) {
            log.warn("delete() ignoring non-matching url {}", s3Url);
            return;
        }
        String key = s3Url.substring(prefix.length());
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            log.info("Deleted s3://{}/{}", bucket, key);
        } catch (NoSuchKeyException e) {
            log.info("delete() — already gone: s3://{}/{}", bucket, key);
        }
    }

    /**
     * Returns the size of an S3 object in bytes (HEAD request).
     */
    public long contentLength(String s3Url) {
        String key = s3Url.substring(("s3://" + bucket + "/").length());
        HeadObjectResponse head = s3Client.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(key).build());
        return head.contentLength();
    }

    /**
     * Opens an input stream for an S3 object, optionally for a byte range.
     * Pass {@code null} for {@code rangeHeader} to fetch the whole object;
     * otherwise pass an HTTP-style {@code "bytes=start-end"} string.
     *
     * <p>Caller owns the returned stream and must close it.
     */
    public ResponseInputStream<GetObjectResponse> openStream(String s3Url, String rangeHeader) {
        String key = s3Url.substring(("s3://" + bucket + "/").length());
        GetObjectRequest.Builder builder = GetObjectRequest.builder().bucket(bucket).key(key);
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            builder.range(rangeHeader);
        }
        return s3Client.getObject(builder.build());
    }

    /**
     * Download an S3 object to a temp file and return its path.
     *
     * @param s3Url  s3://{bucket}/{key}
     * @param suffix file suffix for the temp file (e.g. ".mp4")
     */
    public Path downloadToTemp(String s3Url, String suffix) throws IOException {
        String key = s3Url.substring(("s3://" + bucket + "/").length());
        log.info("Downloading s3://{}/{} ...", bucket, key);
        // createTempFile creates the file on disk; the SDK path overload uses Files.copy
        // internally which throws FileAlreadyExistsException when the target exists.
        // Stream the response manually with REPLACE_EXISTING to avoid that.
        Path temp = Files.createTempFile("s3-download-", suffix);
        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        try (var s3Stream = s3Client.getObject(req)) {
            Files.copy(s3Stream, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Downloaded to {}", temp);
        return temp;
    }
}
