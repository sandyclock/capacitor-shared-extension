package com.capacitor.shareextension.plugin;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.SparseArray;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.getcapacitor.FileUtils;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;


@CapacitorPlugin()
public class ShareExtension extends Plugin {

    @PluginMethod
    public void checkSendIntentReceived(PluginCall call) {
        Intent intent = bridge.getActivity().getIntent();
        Activity activity = bridge.getActivity();
        String action = intent.getAction();
        String type = intent.getType();
        List payload = new ArrayList<JSObject>();
        JSObject ret = new JSObject();
        //Log.v("SHARE", "Intent received, " + type);
        if (Intent.ACTION_SEND.equals(action) && type != null) {
            payload.add(readItemAt(activity, intent, type, 0));
            ret.put("payload", new JSArray(payload));
            call.resolve(ret);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            for (int index = 0; index < intent.getClipData().getItemCount(); index++) {
                payload.add(readItemAt(activity, intent, type, index));
            }
            ret.put("payload", new JSArray(payload));
            call.resolve(ret);
        } else {
            call.reject("No processing needed");
        }
    }

    @PluginMethod
    public void finish(PluginCall call) {
        bridge.getActivity().finish();

        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

  private static final String[] KEY_BROWSER_SCREENSHOT = {"share_screenshot_as_stream",
    "share_full_screen", "file", Intent.EXTRA_STREAM};


  private JSObject readItemAt(Activity activity, Intent intent, String type, int index) {
        JSObject ret = new JSObject();
        String title = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        Uri uri = null;

        if (intent.getClipData() != null && intent.getClipData().getItemAt(index) != null)
            uri = intent.getClipData().getItemAt(index).getUri();

        String url = null;
        Uri copyfileUri = null;
        //Handling web links as url
        if ("text/plain".equals(type) && intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
            url = intent.getStringExtra(Intent.EXTRA_TEXT);
            Bundle extras = intent.getExtras();
          if (extras != null) {
            intent.getBundleExtra(Intent.EXTRA_STREAM);
          }

          Uri screenshotUri =null;
          for (String key : KEY_BROWSER_SCREENSHOT) {
            screenshotUri = intent.getParcelableExtra(key);
            if (screenshotUri != null) {
              break;
            }
          }

           if (screenshotUri!=null){
             Log.d("Decode QR", "found screenshot");
             ShareExtension.decodeQR(ret, activity, screenshotUri);
           }
           else {
             Log.d("Decode QR", "Does NOT found screenshot (info)");

           }
        }
        //Handling files as url
        else if (uri != null) {
            copyfileUri = copyfile(uri);
            url = (copyfileUri != null) ? copyfileUri.toString() : null;
        }

        if (title == null && uri != null)
            title = readFileName(uri);

        String webPath = "";
        if (!("text/plain".equals(type))) {
            webPath = FileUtils.getPortablePath(getContext(), bridge.getLocalUrl(), copyfileUri);
        }

        if (type.startsWith("image/")) {
          Uri imageUri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
          if (imageUri != null) {
            ShareExtension.decodeQR(ret, activity, imageUri);
          }
        }

        ret.put("title", title);
        ret.put("description", null);
        ret.put("type", type);
        ret.put("url", url);
        ret.put("webPath", webPath);
        return ret;
    }

    public String readFileName(Uri uri) {
        Cursor returnCursor =
                getContext().getContentResolver().query(uri, null, null, null, null);
        /*
         * Get the column indexes of the data in the Cursor,
         * move to the first row in the Cursor, get the data,
         * and display it.
         */
        returnCursor.moveToFirst();
        return returnCursor.getString(returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
    }

    Uri copyfile(Uri uri) {
        final String fileName = readFileName(uri);
        File file = new File(getContext().getFilesDir(), fileName);

        try (FileOutputStream outputStream = getContext().openFileOutput(fileName, Context.MODE_PRIVATE);
             InputStream inputStream = getContext().getContentResolver().openInputStream(uri)) {
            IOUtils.copy(inputStream, outputStream);
            return Uri.fromFile(file);
        } catch (FileNotFoundException fileNotFoundException) {
            fileNotFoundException.printStackTrace();
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
        return null;
    }

  interface BitmapResolver {
    Bitmap start(ContentResolver resolver, Uri uri) throws IOException;
  }

  protected static void decodeQR(JSONObject json, final Activity activity, Uri imageUri) {
    BitmapResolver bitmapResover = MediaStore.Images.Media::getBitmap;
    _decodeQR(json, activity, imageUri, bitmapResover);
  }

  protected static void _decodeQR(JSONObject json, final Activity activity, Uri imageUri, BitmapResolver bitmapResolver) {
    Context context = activity.getApplicationContext();
    try {
      json.put("processed", true);
      Bitmap bitmap = bitmapResolver.start(activity.getContentResolver(), imageUri);
      BarcodeDetector detector =
        new BarcodeDetector.Builder(context)
          .setBarcodeFormats(Barcode.DATA_MATRIX | Barcode.QR_CODE)
          .build();
      if (!detector.isOperational()) {
        Log.d("QR_READ", "Could not set up the detector!");
      }
      Frame frame = new Frame.Builder().setBitmap(bitmap).build();
      SparseArray<Barcode> barcodes = detector.detect(frame);
      Log.d("QR_READ", "-barcodeLength-" + barcodes.size());
      Barcode thisCode = null;
      if (barcodes.size() == 0) {
        return;
      }
      JSONArray barcodeArray = new JSONArray();
      for (int iter = 0; iter < barcodes.size(); iter++) {
        thisCode = barcodes.valueAt(iter);
        Log.d("QR_VALUE", "--" + thisCode.rawValue);
        barcodeArray.put(thisCode.rawValue);
      }
      
      json.put("qrStrings", barcodeArray);

      if (barcodes.size() == 0) {
        Log.d("QR_VALUE", "--NODATA");
      } else if (barcodes.size() == 1) {
        thisCode = barcodes.valueAt(0);
        Log.d("QR_VALUE", "--" + thisCode.rawValue);
      } else {
        for (int iter = 0; iter < barcodes.size(); iter++) {
          thisCode = barcodes.valueAt(iter);
          Log.d("QR_VALUE", "--" + thisCode.rawValue);
        }
      }

    } catch (IOException | JSONException e) {
      e.printStackTrace();
    }

  }

}
