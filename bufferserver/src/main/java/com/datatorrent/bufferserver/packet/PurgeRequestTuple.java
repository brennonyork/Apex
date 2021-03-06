/**
 * Copyright (C) 2015 DataTorrent, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.bufferserver.packet;

/**
 * <p>PurgeRequestTuple class.</p>
 *
 * @since 0.3.2
 */
public class PurgeRequestTuple extends GenericRequestTuple
{
  public PurgeRequestTuple(byte[] array, int offset, int length)
  {
    super(array, offset, length);
  }

  public static byte[] getSerializedRequest(String version, String id, long windowId)
  {
    return GenericRequestTuple.getSerializedRequest(version, id, windowId, MessageType.PURGE_REQUEST_VALUE);
  }

}
