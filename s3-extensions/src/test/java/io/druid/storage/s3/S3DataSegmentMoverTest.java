/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.storage.s3;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.metamx.common.MapUtils;
import io.druid.segment.loading.SegmentLoadingException;
import io.druid.timeline.DataSegment;
import io.druid.timeline.partition.NoneShardSpec;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.ServiceException;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.model.StorageObject;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

public class S3DataSegmentMoverTest
{
  private static final DataSegment sourceSegment = new DataSegment(
      "test",
      new Interval("2013-01-01/2013-01-02"),
      "1",
      ImmutableMap.<String, Object>of(
          "key",
          "baseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/index.zip",
          "bucket",
          "main"
      ),
      ImmutableList.of("dim1", "dim1"),
      ImmutableList.of("metric1", "metric2"),
      new NoneShardSpec(),
      0,
      1
  );

  @Test
  public void testMove() throws Exception
  {
    MockStorageService mockS3Client = new MockStorageService();
    S3DataSegmentMover mover = new S3DataSegmentMover(mockS3Client, new S3DataSegmentPusherConfig());

    mockS3Client.putObject("main", new S3Object("baseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/index.zip"));
    mockS3Client.putObject("main", new S3Object("baseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/descriptor.json"));

    DataSegment movedSegment = mover.move(
        sourceSegment,
        ImmutableMap.<String, Object>of("baseKey", "targetBaseKey", "bucket", "archive")
    );

    Map<String, Object> targetLoadSpec = movedSegment.getLoadSpec();
    Assert.assertEquals("targetBaseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/index.zip", MapUtils.getString(targetLoadSpec, "key"));
    Assert.assertEquals("archive", MapUtils.getString(targetLoadSpec, "bucket"));
    Assert.assertTrue(mockS3Client.didMove());
  }

  @Test
  public void testMoveNoop() throws Exception
  {
    MockStorageService mockS3Client = new MockStorageService();
    S3DataSegmentMover mover = new S3DataSegmentMover(mockS3Client, new S3DataSegmentPusherConfig());

    mockS3Client.putObject("archive", new S3Object("targetBaseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/index.zip"));
    mockS3Client.putObject("archive", new S3Object("targetBaseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/descriptor.json"));

    DataSegment movedSegment = mover.move(
        sourceSegment,
        ImmutableMap.<String, Object>of("baseKey", "targetBaseKey", "bucket", "archive")
    );

    Map<String, Object> targetLoadSpec = movedSegment.getLoadSpec();

    Assert.assertEquals("targetBaseKey/test/2013-01-01T00:00:00.000Z_2013-01-02T00:00:00.000Z/1/0/index.zip", MapUtils.getString(targetLoadSpec, "key"));
    Assert.assertEquals("archive", MapUtils.getString(targetLoadSpec, "bucket"));
    Assert.assertFalse(mockS3Client.didMove());
  }

  @Test(expected = SegmentLoadingException.class)
  public void testMoveException() throws Exception
  {
    MockStorageService mockS3Client = new MockStorageService();
    S3DataSegmentMover mover = new S3DataSegmentMover(mockS3Client, new S3DataSegmentPusherConfig());

    mover.move(
        sourceSegment,
        ImmutableMap.<String, Object>of("baseKey", "targetBaseKey", "bucket", "archive")
    );
  }

  private class MockStorageService extends RestS3Service {
    Map<String, Set<String>> storage = Maps.newHashMap();
    boolean moved = false;

    private MockStorageService() throws S3ServiceException
    {
      super(null);
    }

    public boolean didMove() {
      return moved;
    }

    @Override
    public boolean isObjectInBucket(String bucketName, String objectKey) throws ServiceException
    {
      Set<String> objects = storage.get(bucketName);
      return (objects != null && objects.contains(objectKey));
    }

    @Override
    public StorageObject getObjectDetails(String bucketName, String objectKey) throws ServiceException
    {
      if (isObjectInBucket(bucketName, objectKey)) {
        final S3Object object = new S3Object(objectKey);
        object.setStorageClass(S3Object.STORAGE_CLASS_STANDARD);
        return object;
      } else {
        return null;
      }
    }

    @Override
    public Map<String, Object> moveObject(
        String sourceBucketName,
        String sourceObjectKey,
        String destinationBucketName,
        StorageObject destinationObject,
        boolean replaceMetadata
    ) throws ServiceException
    {
      moved = true;
      if(isObjectInBucket(sourceBucketName, sourceObjectKey)) {
        this.putObject(destinationBucketName, new S3Object(destinationObject.getKey()));
        storage.get(sourceBucketName).remove(sourceObjectKey);
      }
      return null;
    }

    @Override
    public S3Object putObject(String bucketName, S3Object object) throws S3ServiceException
    {
      if (!storage.containsKey(bucketName)) {
        storage.put(bucketName, Sets.<String>newHashSet());
      }
      storage.get(bucketName).add(object.getKey());
      return object;
    }
  }
}
