package io.github.xt5tether;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import io.github.xt5tether.ptp.CaptureQueue;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs in a separate UID. Every deleted byte belongs to a generated check fixture. */
public final class PhotoDeletionDeviceCheck extends Instrumentation {
    private Context context;
    private PhotoLibrary library;
    private MainActivity activity;
    private final List<File> fixtures=new ArrayList<>();
    private final List<Uri> publications=new ArrayList<>();
    private int checks;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart() {
        Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try {
            context=getTargetContext();
            require("io.github.xt5tether.checks".equals(context.getPackageName()),"Must target isolated check package");
            library=new PhotoLibrary(context);storageChecks();uiChecks();thumbnailChecks();
            result.putString("stream","\nPASS: "+checks+" real-device gallery/storage assertions in isolated test package; no production photos touched.\n");
        }catch(Throwable e){code=Activity.RESULT_CANCELED;result.putString("stream","\nFAIL: "+android.util.Log.getStackTraceString(e)+"\n");}
        finally {
            try {if(activity!=null)onMain(() -> {activity.finish();return null;});}catch(Exception e){code=Activity.RESULT_CANCELED;result.putString("cleanup",e.toString());}
            if(context!=null)for(Uri uri:publications)try {context.getContentResolver().delete(uri,null,null);}catch(RuntimeException ignored) { }
            for(File file:fixtures)if(file.exists() && !file.delete()){code=Activity.RESULT_CANCELED;result.putString("cleanup","Fixture remained: "+file.getName());}
        }
        finish(code,result);
    }
    private void storageChecks() throws Exception {
        File neighbor=fixture("KEEP.jpg"),local=fixture("PRIVATE.jpg");
        PhotoItem localItem=PhotoItem.local(local);library.delete(localItem);require(!local.exists(),"Private original removed");
        library.delete(localItem);require(neighbor.exists(),"Idempotent delete leaves unselected neighbor");
        File jpeg=fixture("ALBUM.jpg");PhotoItem published=library.publish(PhotoItem.local(jpeg));publications.add(published.uri);
        require(jpeg.exists() && exists(published.uri),"Export retains private and public copies before deletion");
        library.delete(published);require(!jpeg.exists() && !exists(published.uri),"Public and retained private copies removed");
        require(!contains(library.recent(),published.name),"Deleted public capture does not reappear from private copy");
        library.delete(published);require(!exists(published.uri),"Deleted MediaStore item is idempotent");
        File privatePrimary=fixture("EXPORT.jpg");PhotoItem exported=library.publish(PhotoItem.local(privatePrimary));publications.add(exported.uri);
        library.delete(PhotoItem.local(privatePrimary));require(!privatePrimary.exists() && !exists(exported.uri),"Private selection also removes exported duplicate");
        File raf=fixture("RAW.raf");PhotoItem raw=library.publish(PhotoItem.local(raf));publications.add(raw.uri);
        library.delete(raw);require(!raf.exists() && !exists(raw.uri),"RAF private and Downloads copies removed");
        CaptureQueue queue=new CaptureQueue();queue.discovered(1,"PENDING.jpg",100);
        reject(PhotoItem.pending(queue.snapshot().get(0)));require(neighbor.exists(),"Pending rejection leaves saved photos intact");
        File part=new File(library.directory(),"DEVICECHECK.part");write(part);fixtures.add(part);
        reject(PhotoItem.local(part));require(part.exists(),"Partial transfer cannot be deleted through gallery API");
        File outside=new File(context.getCacheDir(),generated("OUTSIDE.jpg"));write(outside);fixtures.add(outside);
        reject(PhotoItem.local(outside));require(outside.exists(),"Outside-directory file retained");
        Uri wrongPath=record(generated("WRONG_PATH.jpg"),"Pictures/XT5DeviceChecks/",0);
        reject(PhotoItem.media(wrongPath,name(wrongPath),1,1));require(exists(wrongPath),"Foreign album path retained");
        Uri pending=record(generated("UNPUBLISHED.jpg"),"Pictures/XT5Tether/",1);
        reject(PhotoItem.media(pending,name(pending),1,1));require(exists(pending),"Unpublished MediaStore record retained");
        Uri ordinary=record("DEVICECHECK_unrelated.jpg","Pictures/XT5Tether/",0);
        reject(PhotoItem.media(ordinary,name(ordinary),1,1));require(exists(ordinary),"Unrecognized filename retained");
        Uri mismatched=record(generated("METADATA.jpg"),"Pictures/XT5Tether/",0);
        reject(PhotoItem.media(mismatched,generated("WRONG_NAME.jpg"),1,1));require(exists(mismatched),"Changed metadata retained");
        reject(PhotoItem.media(Uri.parse("content://media/external/images/media"),generated("COLLECTION.jpg"),1,1));
        reject(PhotoItem.media(Uri.parse("content://other/external/images/media/1"),generated("AUTHORITY.jpg"),1,1));
        require(neighbor.exists(),"Unselected neighbor remains after all storage checks");
        library.delete(PhotoItem.local(neighbor));
        // Remove out-of-library synthetic provider fixtures before UI checks.
        for(Uri uri:publications)context.getContentResolver().delete(uri,null,null);
    }
    private void uiChecks() throws Exception {
        File first=fixture("UI_ONE.jpg"),second=fixture("UI_TWO.jpg"),third=fixture("UI_THREE.jpg");
        PhotoItem album=library.publish(PhotoItem.local(third));publications.add(album.uri);
        activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> field(activity,"service")!=null && list("history").size()==3,"Initial history");
        onMain(() -> {
            TetherService service=(TetherService)field(activity,"service");CaptureQueue queue=(CaptureQueue)field(service,"captureQueue");
            queue.discovered(900,"DEVICECHECK_PENDING.jpg",100);activity.changed();invoke("allPhotos");return null;
        });
        await(() -> field(activity,"gallery")!=null && list("galleryRows").size()==4,"Gallery with pending and saved entries");
        settle();
        onMain(() -> {
            require(list("gallerySaved").size()==3,"Gallery merges exported aliases once");
            bounds();View delete=(View)field(activity,"galleryDelete");int[] normal=location(delete);
            ((Button)field(activity,"galleryMode")).performClick();
            require(selection().active() && selection().size()==0,"Multi-select enters without selecting captures");
            GridView grid=grid();int pending=position("DEVICECHECK_PENDING.jpg");grid.performItemClick(grid.getChildAt(pending),pending,0);
            require(selection().size()==0,"Pending entry cannot join selection");
            int chosen=position(first.getName());grid.performItemClick(grid.getChildAt(chosen),chosen,0);
            require(selection().contains(first.getName()),"Grid tap selects saved photo");
            require(Arrays.equals(normal,location(delete)),"Selection does not move bottom controls");
            ((Button)field(activity,"galleryAll")).performClick();require(selection().size()==3,"Select all excludes pending");
            ((Button)field(activity,"galleryAll")).performClick();require(selection().size()==0,"Cancel all clears selection");
            ((Button)field(activity,"galleryMode")).performClick();
            chosen=position(first.getName());grid.getOnItemLongClickListener().onItemLongClick(grid,grid.getChildAt(chosen),chosen,0);
            require(selection().active() && selection().contains(first.getName()),"Long press enters selection");
            ((Button)field(activity,"galleryDelete")).performClick();AlertDialog confirm=(AlertDialog)field(activity,"deleteConfirmation");
            require(confirm!=null && confirm.isShowing(),"Deletion requires confirmation");confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            require(first.exists() && second.exists(),"Cancel confirmation preserves originals");
            ((Button)field(activity,"galleryDelete")).performClick();return null;
        });
        File arrival=fixture("UI_ARRIVAL.jpg");
        onMain(() -> {
            activity.captured(PhotoItem.local(arrival),0,901);
            require(selection().size()==1 && !selection().contains(arrival.getName()),"Capture arriving during confirmation is not selected");
            ((AlertDialog)field(activity,"deleteConfirmation")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();return null;
        });
        await(() -> !(boolean)field(activity,"deleting") && list("gallerySaved").size()==3,"Single delete and queue refresh");
        require(!first.exists() && second.exists() && third.exists() && arrival.exists(),"Only frozen selected target removed");
        onMain(() -> {
            require(!selection().active(),"Successful delete exits selection");
            require(list("galleryRows").size()==4,"Pending entry and unselected captures retained");
            activity.getWindow().setLayout(Math.round(400*activity.getResources().getDisplayMetrics().density),-1);
            ((Dialog)field(activity,"gallery")).getWindow().setLayout(Math.round(400*activity.getResources().getDisplayMetrics().density),-1);return null;
        });settle();
        onMain(() -> {
            bounds();((Button)field(activity,"galleryMode")).performClick();bounds();
            ((Button)field(activity,"galleryAll")).performClick();require(selection().size()==3,"Narrow gallery selects saved items");
            ((Button)field(activity,"galleryDelete")).performClick();
            ((AlertDialog)field(activity,"deleteConfirmation")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();return null;
        });
        await(() -> !(boolean)field(activity,"deleting") && list("gallerySaved").isEmpty(),"Batch delete finishes");
        require(!second.exists() && !third.exists() && !arrival.exists() && !exists(album.uri),"Batch removes selected private, public and exported duplicates");
        require(library.recent().isEmpty(),"No deleted originals resurrect after batch");
        onMain(() -> {
            require(list("history").isEmpty() && list("galleryRows").size()==1,"History cleared while pending remains visible");
            PhotoItem selected=(PhotoItem)field(activity,"selected");require(selected==null || selected.pending(),"Deleted original is no longer preview selection");
            ((CaptureQueue)field(field(activity,"service"),"captureQueue")).clear();activity.changed();
            require(list("galleryRows").isEmpty(),"Gallery supports empty state");
            require(!((Button)field(activity,"galleryMode")).isEnabled(),"Empty gallery disables multi-select");return null;
        });
    }
    private void thumbnailChecks() throws Exception {
        onMain(() -> {((Dialog)field(activity,"gallery")).dismiss();return null;});
        File first=fixture("STRIP_ONE.jpg"),second=fixture("STRIP_TWO.jpg"),third=fixture("STRIP_KEEP.jpg");
        onMain(() -> {invoke("refreshHistory");return null;});
        await(() -> list("history").size()==3,"Thumbnail fixtures loaded");settle();
        onMain(() -> {
            require(thumbnail(first).performLongClick(),"Thumbnail consumes long press");
            require(stripSelection().active() && stripSelection().size()==1,"Long press selects its photo");
            require(((Button)field(activity,"stripDelete")).isShown(),"Long press exposes inline selection actions");
            thumbnail(second).performClick();
            require(stripSelection().size()==2,"Tap adds another thumbnail to selection");
            CaptureQueue queue=(CaptureQueue)field(field(activity,"service"),"captureQueue");queue.discovered(999,"DEVICECHECK_PENDING.jpg",100);activity.changed();
            View pending=((Map<String,View>)field(activity,"thumbnailViews")).get("pending:999");pending.performLongClick();
            require(stripSelection().size()==2,"Pending thumbnail cannot join multi-select");return null;
        });
        File arrival=fixture("STRIP_ARRIVAL.jpg");
        onMain(() -> {
            activity.captured(PhotoItem.local(arrival),0,1000);
            require(stripSelection().size()==2 && !stripSelection().contains(arrival.getName()),"New capture never joins selection");
            ((Button)field(activity,"stripDelete")).performClick();
            ((AlertDialog)field(activity,"deleteConfirmation")).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            require(first.exists() && second.exists(),"Cancel selected deletion preserves originals");
            ((Button)field(activity,"export")).performClick();return null;
        });
        await(() -> !(boolean)field(activity,"exporting") && !stripSelection().active(),"Batch export completes and exits selection");
        List<Uri> exported=new ArrayList<>();
        for(PhotoItem item:library.recent())if(item.name.equals(first.getName()) || item.name.equals(second.getName())) {
            require(item.inAlbum(),"Each selected JPEG published to album");exported.add(item.uri);publications.add(item.uri);
        }
        require(exported.size()==2 && first.exists() && second.exists(),"Batch export preserves both private originals");
        onMain(() -> {
            thumbnail(first).performLongClick();thumbnail(second).performClick();
            require(stripSelection().size()==2,"Two exported photos can be selected again");
            ((Button)field(activity,"stripDelete")).performClick();
            ((AlertDialog)field(activity,"deleteConfirmation")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();return null;
        });
        await(() -> !(boolean)field(activity,"deleting") && !first.exists() && !second.exists(),"Thumbnail batch deletion finishes");
        for(Uri uri:exported)require(!exists(uri),"Batch delete removes corresponding exported copy");
        require(third.exists() && arrival.exists(),"Unselected and newly captured photos survive batch deletion");
        onMain(() -> {
            // A refresh can replace private keys with MediaStore keys, so choose by logical name.
            PhotoItem saved=list("history").get(0);View cell=((Map<String,View>)field(activity,"thumbnailViews")).get(saved.key);
            cell.performLongClick();
            ((Button)field(activity,"stripAll")).performClick();require(stripSelection().size()==2,"Select all excludes pending thumbnail");
            ((Button)field(activity,"followButton")).performClick();require(!stripSelection().active(),"Cancel returns to normal preview controls");
            ((CaptureQueue)field(field(activity,"service"),"captureQueue")).clear();activity.changed();return null;
        });
    }
    private GallerySelection stripSelection() throws Exception {return (GallerySelection)field(activity,"stripSelection");}
    @SuppressWarnings("unchecked") private View thumbnail(File file) throws Exception {return ((Map<String,View>)field(activity,"thumbnailViews")).get(file.getAbsolutePath());}
    private void bounds() throws Exception {
        View root=((Dialog)field(activity,"gallery")).getWindow().getDecorView();
        for(String key:new String[]{"galleryMode","galleryClose","galleryAll","galleryDelete"}) {
            View view=(View)field(activity,key);int[] p=location(view),r=location(root);
            require(view.getWidth()>0 && p[0]>=r[0] && p[0]+view.getWidth()<=r[0]+root.getWidth(),"Control stays inside gallery: "+key);
        }
    }
    private GridView grid() throws Exception {return (GridView)find(((Dialog)field(activity,"gallery")).getWindow().getDecorView(),GridView.class);}
    private View find(View root,Class<?> type) {if(type.isInstance(root))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=find(((ViewGroup)root).getChildAt(i),type);if(v!=null)return v;}return null;}
    private int position(String name) throws Exception {List<PhotoItem> rows=list("galleryRows");for(int i=0;i<rows.size();i++)if(rows.get(i).name.equals(name))return i;throw new AssertionError("Missing fixture row");}
    private GallerySelection selection() throws Exception {return (GallerySelection)field(activity,"gallerySelection");}
    @SuppressWarnings("unchecked") private List<PhotoItem> list(String name) throws Exception {return (List<PhotoItem>)field(activity,name);}
    private void invoke(String name) throws Exception {Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(activity);}
    private Object field(Object target,String name) throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private <T>T onMain(Callable<T> call) throws Exception {FutureTask<T> task=new FutureTask<>(call);runOnMainSync(task);return task.get();}
    private void await(Callable<Boolean> condition,String message) throws Exception {for(int i=0;i<200;i++){if(onMain(condition))return;SystemClock.sleep(50);}throw new AssertionError("Timed out: "+message);}
    private void settle(){waitForIdleSync();SystemClock.sleep(150);waitForIdleSync();}
    private int[] location(View v){int[] p=new int[2];v.getLocationOnScreen(p);return p;}
    private File fixture(String suffix) throws Exception {File file=new File(library.directory(),generated(suffix));file.getParentFile().mkdirs();write(file);fixtures.add(file);return file;}
    private String generated(String suffix){return System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,8)+"_DEVICECHECK_"+suffix;}
    private void write(File file) throws Exception {try(FileOutputStream out=new FileOutputStream(file)){Bitmap b=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);b.eraseColor(Color.GREEN);b.compress(Bitmap.CompressFormat.JPEG,90,out);b.recycle();}}
    private Uri record(String name,String path,int pending) throws Exception {
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg");values.put(MediaStore.MediaColumns.RELATIVE_PATH,path);values.put(MediaStore.MediaColumns.IS_PENDING,pending);
        Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("Fixture insert failed");publications.add(uri);return uri;
    }
    private String name(Uri uri){try(android.database.Cursor c=context.getContentResolver().query(uri,new String[]{MediaStore.MediaColumns.DISPLAY_NAME},null,null,null)){if(c==null || !c.moveToFirst())throw new AssertionError("Fixture missing");return c.getString(0);}}
    private boolean exists(Uri uri){try(android.database.Cursor c=context.getContentResolver().query(uri,new String[]{MediaStore.MediaColumns._ID},null,null,null)){return c!=null && c.moveToFirst();}}
    private boolean contains(List<PhotoItem> items,String name){for(PhotoItem item:items)if(item.name.equals(name))return true;return false;}
    private void reject(PhotoItem item) throws Exception {try {library.delete(item);throw new AssertionError("Unsafe delete was accepted");}catch(IOException expected){checks++;}}
    private void require(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
