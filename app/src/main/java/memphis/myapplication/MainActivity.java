package memphis.myapplication;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.VisibleForTesting;
import android.support.constraint.ConstraintLayout;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import net.named_data.jndn.ContentType;
import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.InterestFilter;
import net.named_data.jndn.MetaInfo;
import net.named_data.jndn.Name;
import net.named_data.jndn.OnData;
import net.named_data.jndn.OnInterestCallback;
import net.named_data.jndn.OnRegisterFailed;
import net.named_data.jndn.OnRegisterSuccess;
import net.named_data.jndn.encoding.ElementListener;
import net.named_data.jndn.encoding.EncodingException;
import net.named_data.jndn.security.KeyChain;
import net.named_data.jndn.security.SecurityException;
import net.named_data.jndn.security.identity.IdentityManager;
import net.named_data.jndn.security.identity.MemoryIdentityStorage;
import net.named_data.jndn.security.identity.MemoryPrivateKeyStorage;
import net.named_data.jndn.security.pib.PibImpl;
import net.named_data.jndn.security.tpm.TpmBackEnd;
import net.named_data.jndn.transport.TcpTransport;
import net.named_data.jndn.transport.Transport;
import net.named_data.jndn.util.Blob;
import net.named_data.jndn.util.SegmentFetcher;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.os.Environment.getExternalStorageDirectory;
import static com.google.zxing.integration.android.IntentIntegrator.QR_CODE_TYPES;

public class MainActivity extends AppCompatActivity {

    MainActivity mainActivity = this;
    public static Context mContext;
    String retrieved_data = "";
    MemoryIdentityStorage identityStorage;
    MemoryPrivateKeyStorage privateKeyStorage;
    IdentityManager identityManager;
    KeyChain keyChain;
    public Face face;
    // public Face face2;
    public FaceProxy faceProxy;
    // think about adding a memoryContentCache instead of faceProxy
    List<String> filesStrings = new ArrayList<String>();
    List<Uri> filesList = new ArrayList<Uri>();
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    private final int FILE_SELECT_REQUEST_CODE = 0;
    private final int FILE_QR_REQUEST_CODE = 1;
    private final int SCAN_QR_REQUEST_CODE = 2;

    // maybe add a thread that runs and constantly calls face.processEvents; Ashlesh said that if
    // we have 2 faces, they could be blocking one another if they are on the same thread. So, if
    // we keep the two faces, they'll need their own threads.

    private boolean appThreadShouldStop = true;
    private boolean has_setup_security = false;

    public void setup_security() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                face = new Face();
                faceProxy = new FaceProxy();
                // look at File equivalents to Memory in jndn; That should accomplish your basic
                // idea while using Nick's use of jndn
                // come back to this when we want a perm solution; will need to integrate SQLite3, but will solve storage questions
                identityStorage = new MemoryIdentityStorage();
                privateKeyStorage = new MemoryPrivateKeyStorage();
                identityManager = new IdentityManager(identityStorage, privateKeyStorage);
                keyChain = new KeyChain(identityManager);
                //keyChain.setFace(faces[i]);
                keyChain.setFace(face);

                // NOTE: This is based on apps-NDN-Whiteboard/helpers/Utils.buildTestKeyChain()...
                Name testIdName = new Name("/test/identity");
                Name defaultCertificateName;
                try {
                    defaultCertificateName = keyChain.createIdentityAndCertificate(testIdName);
                    keyChain.getIdentityManager().setDefaultIdentity(testIdName);
                    Log.d("setup_security", "Certificate was generated.");

                } catch (SecurityException e2) {
                    defaultCertificateName = new Name("/bogus/certificate/name");
                }
                // faces[i].setCommandSigningInfo(keyChain, defaultCertificateName);
                face.setCommandSigningInfo(keyChain, defaultCertificateName);
                has_setup_security = true;
                Log.d("setup_security", "Security was setup successfully");
                    /*try {
                        // faces[i].processEvents();
                        face.processEvents();
                    } catch (IOException | EncodingException e) {
                        e.printStackTrace();
                    }*/
            }
            //}
        });
        thread.run();
    }

    private final Thread appThread = new Thread(new Runnable() {
        @Override
        public void run() {
            if (!has_setup_security) {
                setup_security();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            while (!appThreadShouldStop) {
                try {
                    face.processEvents();
                    Thread.sleep(100);
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    });

    private void startAppThread() {
        if (!appThread.isAlive()) {
            appThreadShouldStop = false;
            appThread.start();
        }
    }

    private void stopNetworkThread() {
        appThreadShouldStop = true;
    }

    protected boolean networkThreadIsRunning() {
        return appThread.isAlive();
    }

    public Runnable makeToast(final String s) {
        Runnable show_toast = new Runnable() {
            public void run() {
                Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show();
            }
        };
        return show_toast;
    }

    public static final String EXTRA_MESSAGE = "com.example.myfirstapp.MESSAGE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        this.filesList = new ArrayList<Uri>();
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        int permission = ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                    this,
                    PERMISSIONS_STORAGE,
                    REQUEST_EXTERNAL_STORAGE
            );
        }
        startAppThread();
        // this is not available when we are signing up, we need a universal main function to set this
        // context so I can use it elsewhere
        mContext = getApplicationContext();
        // check if logged in; if not, go to Login Page. From there you can access the signup page.
        // this is where we should check that we have all of our security setup maybe? If not, we
        // need to generate things again.
    }

    /**
     * Called when the user taps the fetch data button
     */
    public void fetch_data(View view) {
        Log.d("fetch_data", "Called fetch_data");
        EditText editText = (EditText) findViewById(R.id.editText);
        String message = editText.getText().toString();
        Log.d("fetch_data", "Message from editText: " + message);
        final Interest interest = new Interest(new Name(message));
        Log.d("fetch_data", "Interest: " + interest.getName().toString());
        // interest.setInterestLifetimeMilliseconds(20000);

        final boolean[] enabled = new boolean[]{true};
        SegmentFetcher.fetch(
                face,
                interest,
                new SegmentFetcher.VerifySegment() {
                    @Override
                    public boolean verifySegment(Data data) {
                        Log.d("VerifySegment", "We just return true.");
                        return true;
                    }
                },
                new SegmentFetcher.OnComplete() {
                    @Override
                    public void onComplete(Blob content) {
                        // Log.d("fetch_data onComplete", "we got content");
                        // retrieved_data = new String(content.getImmutableArray());
                        // Log.d("fetch_data onComplete", "ShortContent: " + retrieved_data);
                        FileManager manager = new FileManager(getApplicationContext());
                        // String interestName = manager.removeAppPrefix(interest.getName().toString());
                        // boolean wasSaved = manager.saveContentToFile(content, interestName);
                        boolean wasSaved = manager.saveContentToFile(content, interest.getName().toUri());
                        if(wasSaved) {
                            String msg = "We got content.";
                            runOnUiThread(makeToast(msg));
                        }
                        else {
                            String msg = "Failed to save retrieved content";
                            runOnUiThread(makeToast(msg));
                        }
                    }
                },
                new SegmentFetcher.OnError() {
                    @Override
                    public void onError(SegmentFetcher.ErrorCode errorCode, String message) {
                        Log.d("fetch_data onError", message);
                        runOnUiThread(makeToast(message));
                    }
                });
    }

    public void register_with_NFD(View view) {
        EditText editText = findViewById(R.id.editText);
        String msg = editText.getText().toString();
        try {
            Name name = new Name(msg);
            register_with_NFD(name);
        } catch (IOException | PibImpl.Error e) {
            e.printStackTrace();
        }
    }

    public void register_with_NFD(Name name) throws IOException, PibImpl.Error {

        if (!has_setup_security) {
            setup_security();
            while (!has_setup_security)
                try {
                    wait(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
        }
        final boolean[] enabled = new boolean[]{true};
        try {
            Log.d("register_with_nfd", "Starting registration process.");
            //long prefixId = face2.registerPrefix(name,
            long prefixId = face.registerPrefix(name,
                    onDataInterest,
                    new OnRegisterFailed() {
                        @Override
                        public void onRegisterFailed(Name prefix) {
                            // enabled[0] = false;
                            Log.d("OnRegisterFailed", "Registration Failure");
                            String msg = "Registration failed for prefix: " + prefix.toUri();
                            runOnUiThread(makeToast(msg));
                            // show_dialog(prefix, true);
                        }
                    },
                    new OnRegisterSuccess() {
                        @Override
                        public void onRegisterSuccess(Name prefix, long registeredPrefixId) {
                            // enabled[0] = false;
                            Log.d("OnRegisterSuccess", "Registration Success for prefix: " + prefix.toUri() + ", id: " + registeredPrefixId);
                            String msg = "Successfully registered prefix: " + prefix.toUri();
                            runOnUiThread(makeToast(msg));
                            // final CharSequence text = "Successfully registered prefix: " + prefix.toString();
                        }
                    });
        }
        catch (IOException | SecurityException e) {
            e.printStackTrace();
        }
    }

    public void publishData(Blob blob, Name prefix) {
        try {
            ArrayList<Data> fileData = new ArrayList<>();
            Log.d("publishData", "Publishing with prefix: " + prefix);
            for (Data data : packetize(blob, prefix)) {
                keyChain.sign(data);
                fileData.add(data);
            }
            faceProxy.putInCache(fileData);
        }
          catch (PibImpl.Error | SecurityException | TpmBackEnd.Error | KeyChain.Error e) {
              e.printStackTrace();
          }
    }

    public void select_files(View view) {
        /* final ListView lv = (ListView) findViewById(R.id.listview);
        List<String> filesStrings = new ArrayList<String>();*/
        // ACTION_OPEN_DOCUMENT is the intent to choose a file via the system's file
        // browser.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);

        // Filter to only show results that can be "opened", such as a
        // file (as opposed to a list of contacts or timezones)

        // To search for all documents available via installed storage providers,
        // it would be "*/*".
        intent.setType("*/*");

        startActivityForResult(intent, FILE_SELECT_REQUEST_CODE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode,
                                 Intent resultData) {

        Log.d("onActivityResult", "requestCode: " + requestCode);
        Uri uri = null;
        if (resultData != null) {
            if (requestCode == FILE_SELECT_REQUEST_CODE) {
                final ListView lv = (ListView) findViewById(R.id.listview);

                uri = resultData.getData();
                String path = getFilePath(uri);

                if (path != null) {
                    // Log.d("file select result", "String s: " + uri.getPath().toString());
                    filesList.add(uri);
                    // filesStrings.add(uri.toString());
                    filesStrings.add(path);
                    ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, filesStrings);
                    lv.setAdapter(adapter);
                    AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
                    builder.setTitle("You selected a file").setMessage(path).show();
                    byte[] bytes;
                    try {
                        InputStream is = MainActivity.this.getContentResolver().openInputStream(uri);
                        bytes = IOUtils.toByteArray(is);
                        Log.d("select file activity", "file byte array size: " + bytes.length);
                    } catch (IOException e) {
                        Log.d("onItemClick", "failed to byte");
                        e.printStackTrace();
                        bytes = new byte[0];
                    }
                    Log.d("file selection result", "file path: " + path);
                    final Blob blob = new Blob(bytes, true);
                    String prefix = FileManager.addAppPrefix(path);
                    Log.d("added file prefix", "prefix: " + prefix);
                    publishData(blob, new Name(prefix));
                    // QRExchange.makeQRFileCode(getApplicationContext(), path);
                }
                else {
                    Toast.makeText(this, "File path could not be resolved.", Toast.LENGTH_LONG).show();
                }
            }
            // We received a request to display a QR image
            else if (requestCode == FILE_QR_REQUEST_CODE) {
                try {
                    // set up a new Activity for displaying. This way the back button brings us back
                    // to main activity.
                    Intent display = new Intent(this, DisplayFileQRCode.class);
                    display.setData(resultData.getData());
                    startActivity(display);
                } catch (NullPointerException e) {
                    e.printStackTrace();
                }
            } else if (requestCode == SCAN_QR_REQUEST_CODE) {
                IntentResult result = IntentIntegrator.parseActivityResult(IntentIntegrator.REQUEST_CODE, resultCode, resultData);
                if (result == null) {
                    Toast.makeText(this, "Null", Toast.LENGTH_LONG).show();
                }
                if (result != null) {
                    // check resultCode to determine what type of code we're scanning, file or friend

                    if (result.getContents() == null) {
                        Toast.makeText(this, "Nothing is here", Toast.LENGTH_LONG).show();
                    } else {
                        String content = result.getContents();
                        // need to check this content to determine if we are scanning file or friend code
                        Toast.makeText(this, content, Toast.LENGTH_LONG).show();
                        FileManager manager = new FileManager(getApplicationContext());
                    }
                } else {
                    super.onActivityResult(requestCode, resultCode, resultData);
                }
            } else {
                Log.d("onActivityResult", "Unexpected activity requestcode caught");
            }
        }
    }

    public String getFilePath(Uri uri) {
        String selection = null;
        String[] selectionArgs = null;
        if (DocumentsContract.isDocumentUri(getApplicationContext(), uri)) {
            if (uri.getAuthority().equals("com.android.externalstorage.documents")) {
                final String docId = DocumentsContract.getDocumentId(uri);
                Log.d("file selection", "docId: " + docId);
                final String[] split = docId.split(":");
                return Environment.getExternalStorageDirectory() + "/" + split[1];
            }
            else if (uri.getAuthority().equals("com.android.providers.downloads.documents")) {
                final String id = DocumentsContract.getDocumentId(uri);
                uri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));
            }
            else if (uri.getAuthority().equals("com.android.providers.media.documents")) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];
                if ("image".equals(type)) {
                    uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }
                selection = "_id=?";
                selectionArgs = new String[]{split[1]};
            }
        }

        if (uri.getScheme().equalsIgnoreCase("content")) {
            String[] projection = {MediaStore.Images.Media.DATA};
            Cursor cursor = null;
            try {
                cursor = getApplicationContext().getContentResolver().query(uri, projection, selection, selectionArgs, null);
                int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                if (cursor.moveToFirst()) {
                    return cursor.getString(column_index);
                }
            } catch (Exception e) {
                return null;
            }
        }
        else if (uri.getScheme().equalsIgnoreCase("file")) {
            return uri.getPath();
        }
        return null;
    }

    /**
     * Start a file selection activity to find a QR image to display. This is triggered by pressing
     * the "Display QR" button.
     * @param view The view of MainActivity passed by our button press.
     */
    public void lookup_file_QR(View view) {
        // ACTION_GET_CONTENT is used for reading; no modifications
        // We're going to find a png file of our choosing (should be used for displaying QR codes,
        // but it can display any image)
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        // fix this: this displays every file. We should limit the scope to a specific directory and
        // image files, if possible.
        intent.setType("*/*");
        startActivityForResult(intent, FILE_QR_REQUEST_CODE);
    }

    /**
     * initiate scan for QR codes upon button press
     */
    public void scanFileQR(View view) {
        IntentIntegrator scanner = new IntentIntegrator(this);
        // only want QR code scanner
        scanner.setDesiredBarcodeFormats(QR_CODE_TYPES);
        scanner.setOrientationLocked(true);
        // back facing camera id
        scanner.setCameraId(0);
        Intent intent = scanner.createScanIntent();
        startActivityForResult(intent, SCAN_QR_REQUEST_CODE);
    }

    public ArrayList<Data> packetize(Blob raw_blob, Name prefix) {
        final int VERSION_NUMBER = 0;
        final int DEFAULT_PACKET_SIZE = 8000;
        int PACKET_SIZE = (DEFAULT_PACKET_SIZE > raw_blob.size()) ? raw_blob.size() : DEFAULT_PACKET_SIZE;
        ArrayList<Data> datas = new ArrayList<>();
        int segment_number = 0;
        ByteBuffer byteBuffer = raw_blob.buf();
        do {
            // need to check for the size of the last segment; if lastSeg < PACKET_SIZE, then we
            // should not send an unnecessarily large packet. Also, if smaller, we need to prevent BufferUnderFlow error
            if(byteBuffer.remaining() < PACKET_SIZE) {
                PACKET_SIZE = byteBuffer.remaining();
            }
            Log.d("packetize things", "PACKET_SIZE: " + PACKET_SIZE);
            byte[] segment_buffer = new byte[PACKET_SIZE];
            Data data = new Data();
            Name segment_name = new Name(prefix);
            segment_name.appendVersion(VERSION_NUMBER);
            segment_name.appendSegment(segment_number);
            data.setName(segment_name);
            try {
                Log.d("packetize things", "full data name: " + data.getFullName().toString());
            } catch (EncodingException e) {
                Log.d("packetize things", "unable to print full name");
            }
            try {
                Log.d("packetize things", "byteBuffer position: " + byteBuffer.position());
                byteBuffer.get(segment_buffer, 0, PACKET_SIZE);
            } catch (IndexOutOfBoundsException e) {
                e.printStackTrace();
            }
            data.setContent(new Blob(segment_buffer));
            MetaInfo meta_info = new MetaInfo();
            meta_info.setType(ContentType.BLOB);
            // not sure what is a good freshness period
            meta_info.setFreshnessPeriod(30000);
            if (!byteBuffer.hasRemaining()) {
                // Set the final component to have a final block id.
                Name.Component finalBlockId = Name.Component.fromSegment(segment_number);
                meta_info.setFinalBlockId(finalBlockId);
            }
            data.setMetaInfo(meta_info);
            datas.add(data);
            segment_number++;
        } while (byteBuffer.hasRemaining());
        return datas;
    }

    private final OnInterestCallback onDataInterest = new OnInterestCallback() {
        @Override
        public void onInterest(Name prefix, Interest interest, Face face, long interestFilterId,
                               InterestFilter filterData) {
            Log.d("OnInterestCallback", "Called OnInterestCallback with Interest: " + interest.getName().toUri());
            faceProxy.process(interest, mainActivity);
        }
    };

    // maybe we need our own onData callback since it is used in expressInterest (which is called by the SegmentFetcher)
}
