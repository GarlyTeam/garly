/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pro.garlyapp.app;



public class Application extends android.app.Application {

  

  @Override
  public void onCreate() {
      super.onCreate();
      // QA builds record crashes to Download/Garly-QA so a blank screen on a
      // phone with no cable still leaves a stack trace behind. Reflection because
      // the recorder lives in the qa source set, which this one cannot see.
      try {
          Class.forName("pro.garlyapp.app.QaCrashLog")
                  .getMethod("install", android.content.Context.class)
                  .invoke(null, this);
      } catch (Throwable ignored) {
          // Release builds simply do not have it.
      }
  }
}
