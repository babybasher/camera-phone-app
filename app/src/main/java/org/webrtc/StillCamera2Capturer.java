/*
 *  Copyright 2016 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.content.Context;
import android.hardware.camera2.CameraManager;

/** homecam: WebRTC's Camera2Capturer, using StillCamera2Session (no digital stabilization). */
public class StillCamera2Capturer extends CameraCapturer {
  /** homecam: the zoom to ask for (below 1 is the wide angle lens on phones that offer it that way). */
  public static volatile float zoomRatio = 1f;
  /** homecam: cameras the phone doesn't list but will open (e.g. a wide angle lens), found by the app. */
  public static volatile String[] extraNames = new String[0];

  private final CameraManager cameraManager;

  public StillCamera2Capturer(Context context, String cameraName, CameraEventsHandler eventsHandler) {
    super(cameraName, eventsHandler, new Camera2Enumerator(context) {
      @Override
      public String[] getDeviceNames() {
        java.util.List<String> l = new java.util.ArrayList<>(java.util.Arrays.asList(super.getDeviceNames()));
        for (String n : extraNames) if (!l.contains(n)) l.add(n);
        return l.toArray(new String[0]);
      }
    });

    cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
  }

  @Override
  protected void createCameraSession(CameraSession.CreateSessionCallback createSessionCallback,
      CameraSession.Events events, Context applicationContext,
      SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height,
      int framerate) {
    StillCamera2Session.create(createSessionCallback, events, applicationContext, cameraManager,
        surfaceTextureHelper, cameraName, width, height, framerate);
  }
}
