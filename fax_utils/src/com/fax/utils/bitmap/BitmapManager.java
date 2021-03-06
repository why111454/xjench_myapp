package com.fax.utils.bitmap;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v4.util.LruCache;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import com.fax.utils.http.HttpUtils;
import com.fax.utils.task.ActivityAsyncTask;
import com.fax_utils.BuildConfig;

/**
 * 图片缓存管理类，有效防止图片引起的OOM
 * 设计了三重缓存：图片缓存（Bitmap），字节缓存（Byte），本地缓存(文件)
 * 提供bindView方法，方便的异步加载图片到View
 * @author linlinfaxin@163.com
 */
public class BitmapManager{
    private static boolean DEBUG = BuildConfig.DEBUG;
    static private Bitmap Default_LoadingFailBitmap;//默认的加载失败的图片
    static private Bitmap Default_LoadingBitmap;//默认的加载失败的图片
	static private File cacheImgDir;//图片缓存到本地的目录
	static final private String ImgFileNameExtension="";//本地缓存的图片的额外后缀名
	private static int MAX_BitmapList_Size = 5*1024*1024;//最大的bitmap缓存大小
	private static int MAX_ImageByteList_Size = 2*1024*1024;//最大的bytes缓存大小
	private static final int MAX_Bitmap_Width = 1280;//最大的可以缓存的BitmapList的宽（如果大于这个值会自动缩放）
	private static final int MAX_Bitmap_Height = 1280;//最大的可以缓存进BitmapList的高（如果大于这个值会自动缩放）
	private static final int DefaultLoadNetDelay = 200;//从网络下载图片的等待延迟（有利于ListView的效率）
	/**内容图片缓存，用来缓存之前载入过的bitmap*/
	private static LruCache<String, Bitmap> bitmapList;
	/**内容字节缓存，用来缓存之前载入过的bitmap的字节*/
	private static LruCache<String,byte[]> imageBytesList;
    static{
        initMaxCache(MAX_BitmapList_Size, MAX_ImageByteList_Size);
    }

	public static void init(Context context){
		init(context, null,null, new File(context.getExternalCacheDir(), "imgCache"));
	}
	/**
	 * 初始化BitmapManager类的一些参数，必须在使用前调用
	 * @param context 上下文
	 * @param loadingFailBitmap 默认的加载失败的图片
	 * @param imgDir 默认本地图片缓存目录，为空则不缓存到本地文件
	 */
	public static void init(Context context,Bitmap loadingFailBitmap,Bitmap loadingBitmap,File imgDir){
        final int memClass = ((ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass();
		initMaxCache(1024 * 1024 * memClass / 8, 1024 * 1024 * memClass / 16);
		if(loadingFailBitmap==null) loadingFailBitmap=Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        BitmapManager.Default_LoadingFailBitmap =loadingFailBitmap;
        BitmapManager.Default_LoadingBitmap =loadingBitmap;
		BitmapManager.cacheImgDir=imgDir;
		if (imgDir!=null) {
			try {
                File noMediaFile = new File(imgDir, ".nomedia");
				if(!noMediaFile.exists() && !noMediaFile.createNewFile()){
                    if(DEBUG) Log.w(BitmapManager.class.getSimpleName(), ".nomedia create fail");
                }
			} catch (Exception ignore) {
			}
			if (!BitmapManager.cacheImgDir.exists() && !BitmapManager.cacheImgDir.mkdirs()){
                if(DEBUG) Log.w(BitmapManager.class.getSimpleName(), "cacheImgDir mkdirs fail");
            }
		}
	}
    private static void initMaxCache(int bitmapSize,int byteSize){
        if(DEBUG) Log.w(BitmapManager.class.getSimpleName(), "initMax bitmapSize:"+bitmapSize+",byteSize:"+byteSize);

		MAX_BitmapList_Size=bitmapSize;
        if(bitmapList!=null) bitmapList.evictAll();
        bitmapList = new LruCache<String, Bitmap>(bitmapSize){
            @TargetApi(Build.VERSION_CODES.KITKAT)
			@Override
            protected int sizeOf(String key, Bitmap value) {
                if(value==null) return 0;
                if(Build.VERSION.SDK_INT>=19) return value.getAllocationByteCount();
                return value.getRowBytes() * value.getHeight();
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                super.entryRemoved(evicted, key, oldValue, newValue);
                if(DEBUG) Log.w(BitmapManager.class.getSimpleName(), "recycle bitmap:"+key);
                if(oldValue!=null && !cantRecycleSet.contains(key)) oldValue.recycle();
            }
        };
		MAX_ImageByteList_Size=byteSize;
        if(imageBytesList!=null) imageBytesList.evictAll();
        imageBytesList =new LruCache<String,byte[]>(byteSize){
            @Override
            protected int sizeOf(String key, byte[] value) {
                if(value==null) return 0;
                return value.length;
            }
        };
	}
	/**bindView时，用于将view与url绑定，避免多次bandView造成的显示图片出错*/
	private static ConcurrentHashMap<Integer, String> bindViewMap =new ConcurrentHashMap<Integer, String>();
    /**一切不能被回收的图片的集合 */
    private static HashSet<String> cantRecycleSet = new HashSet<String>();//注意，还未测试这个的有效性

	/**
	 * 异步绑定一个View，让它显示Bitmap（如果加载失败或者url为空，则显示BitmapManager.Default_LoadingFailBitmap）
	 * @param view 绑定的view，如果是ImageView则设置src，否则设置background
	 * @param url 图片路径
	 */
	public static LoadBitmapTask bindView(final View view,final String url){
		return bindView(view, Default_LoadingBitmap, Default_LoadingFailBitmap, Default_LoadingFailBitmap, url, DefaultLoadNetDelay, null);
	}
	public static LoadBitmapTask bindView(final View view,final String url, int delay){
		return bindView(view, Default_LoadingBitmap, Default_LoadingFailBitmap, Default_LoadingFailBitmap, url, delay, null);
	}
	/**
	 * 异步绑定一个View，让它显示Bitmap（如果加载失败或者url为空，则显示BitmapManager.Default_LoadingFailBitmap）
	 * @param view 绑定的view，如果是ImageView则设置src，否则设置background
	 * @param loadingBitmap 加载过程中view显示的bitmap,为空则显示透明
	 * @param url 图片路径
	 */
	public static LoadBitmapTask bindView(final View view,Bitmap loadingBitmap,final String url){
		return bindView(view, loadingBitmap, Default_LoadingFailBitmap, Default_LoadingFailBitmap, url, DefaultLoadNetDelay, null);
	}

	/**
	 * 异步绑定一个View，让它显示Bitmap（如果加载失败，则显示BitmapManager.Default_LoadingFailBitmap）
	 * @param view 绑定的view，如果是ImageView则设置src，否则设置background
	 * @param loadingBitmap 加载过程中view显示的bitmap,为空则显示透明
	 * @param noImgBitmap 如果url为空显示的图片,为空则显示BitmapManager.Default_LoadingFailBitmap
	 * @param loadFailBitmap 如果加载图片失败显示的图片,为空则显示BitmapManager.Default_LoadingFailBitmap
	 * @param url 图片路径
	 */
	public static LoadBitmapTask bindView(final View view,Bitmap loadingBitmap,Bitmap noImgBitmap,Bitmap loadFailBitmap,final String url){
		return bindView(view, loadingBitmap, noImgBitmap, loadFailBitmap, url, DefaultLoadNetDelay, null);
	}
	/**
	 * 异步绑定一个View，让它显示Bitmap（如果加载失败，则显示BitmapManager.Default_LoadingFailBitmap）
	 * @param view 绑定的view，如果是ImageView则设置src，否则设置background
	 * @param loadingBitmap 加载过程中view显示的bitmap,为空则显示透明
	 * @param noImgBitmap 如果url为空显示的图片,为空则显示BitmapManager.Default_LoadingFailBitmap
	 * @param loadFailBitmap 如果加载图片失败显示的图片,为空则显示BitmapManager.Default_LoadingFailBitmap
	 * @param url 图片路径
	 * @param delay 从网络载入的延迟等待
	 */
	public static LoadBitmapTask bindView(final View view,Bitmap loadingBitmap,Bitmap noImgBitmap,Bitmap loadFailBitmap,final String url,int delay){
		return bindView(view, loadingBitmap, noImgBitmap, loadFailBitmap, url, delay, null);
	}
	/**
	 * 异步绑定一个View，让它显示Bitmap（如果加载失败，则显示BitmapManager.Default_LoadingFailBitmap）
	 * @param view 绑定的view，如果是ImageView则设置src，否则设置background
	 * @param loadingBitmap 加载过程中view显示的bitmap,为空则显示透明
	 * @param noImgBitmap 如果url为空显示的图片,为空则显示BitmapManager.Default_LoadingFailBitmap
	 * @param loadFailBitmap 如果加载图片失败显示的图片,为空则显示BitmapManager.Default_LoadingFailBitmap
	 * @param bitmapLoadingListener 图片进度监听接口，不需要在这个接口中setImageBitmap
	 * @param url 图片路径
	 */
	public static LoadBitmapTask bindView(final View view,Bitmap loadingBitmap,Bitmap noImgBitmap,Bitmap loadFailBitmap,final String url,final BitmapLoadingListener bitmapLoadingListener){
		return bindView(view, loadingBitmap, noImgBitmap, loadFailBitmap, url, DefaultLoadNetDelay, bitmapLoadingListener);
	}
	/**
	 * 异步绑定一个View，让它显示Bitmap（如果加载失败，则显示BitmapManager.Default_LoadingFailBitmap）
	 * @param view 绑定的view，如果是ImageView则设置src，否则设置background
	 * @param loadingBitmap 加载过程中view显示的bitmap,为空则显示透明
	 * @param noImgBitmap 如果url为空显示的图片,为空则显示BitmapManager.Default_LoadingFailBitmap
	 * @param loadFailBitmap 如果加载图片失败显示的图片,为空则显示BitmapManager.Default_LoadingFailBitmap
	 * @param url 图片路径
	 * @param delay 从网络载入的延迟等待
	 * @param bitmapLoadingListener 图片进度监听接口，不需要在这个接口中setImageBitmap
	 */
	public static LoadBitmapTask bindView(final View view,Bitmap loadingBitmap,Bitmap noImgBitmap,final Bitmap loadFailBitmap,final String url,final int delay,final BitmapLoadingListener bitmapLoadingListener){
		if(view==null) return null;
		if(TextUtils.isEmpty(url)){
			setImageToView(view,noImgBitmap!=null?noImgBitmap: Default_LoadingFailBitmap);
			return null;
		}
		if(cacheImgDir==null) init(view.getContext());

		if(!(view instanceof ProgressImageView))//不对NetImageView展示进度图片，会有默认进度
            setImageToView(view, loadingBitmap );


		bindViewMap.put(view.hashCode(), url);//将view的hash与url绑定
        final LoadChecker checker = new LoadChecker() {
            @Override
            public boolean canLoad() {
                return url.equals(bindViewMap.get(view.hashCode()));
            }
        };

        if(view instanceof RecycleImageView && !((RecycleImageView) view).isCanRecycle()){
            cantRecycleSet.add(url);
        }

		return BitmapManager.getBitmapInBg(view.getContext(), url, new BitmapLoadingListener() {
            @Override
            public void onBitmapLoadFinish(Bitmap bitmap, boolean isLoadSuccess) {
                if (checker.canLoad()) {//检查是否依然绑定
                    bindViewMap.remove(view.hashCode());
                    if (isLoadSuccess) setImageToView(view, bitmap);
                    else setImageToView(view, loadFailBitmap != null ? loadFailBitmap : Default_LoadingFailBitmap);

                    if (view instanceof RecycleImageView) {
                        ((RecycleImageView) view).onLoadUrlFinish(url);
                    }
                    if (bitmapLoadingListener != null)
                        bitmapLoadingListener.onBitmapLoadFinish(bitmap, isLoadSuccess);
                }
            }

            @Override
            public void onBitmapLoading(int progress) {
                if (checker.canLoad()) {//检查是否依然绑定
                    if (view instanceof ProgressImageView) ((ProgressImageView) view).setProgress(progress);
                    if (bitmapLoadingListener != null) bitmapLoadingListener.onBitmapLoading(progress);
                }
            }
        }, delay, checker);
	}
	/**从view中获取bitmap，可能为null */
	public static Bitmap getBitmapFromView(View view){
		try {
			if (view instanceof ImageView) {
				return ((BitmapDrawable) ((ImageView) view).getDrawable()).getBitmap();
			} else {
				return ((BitmapDrawable) view.getBackground()).getBitmap();
			}
		} catch (Exception e) {
			return null;
		}
	}
	@SuppressWarnings("deprecation")
	private static void setImageToView(View view,Bitmap bitmap){
		if(view instanceof ImageView){
			if(bitmap==null) ((ImageView) view).setImageDrawable(new ColorDrawable(Color.TRANSPARENT));
			else ((ImageView) view).setImageBitmap(bitmap);
		}else{
			if(bitmap==null) view.setBackgroundColor(Color.TRANSPARENT);
			else view.setBackgroundDrawable(new BitmapDrawable(view.getContext().getResources(),bitmap));
		}
	}
    /**
     * 异步获取图片。从listen中监听，listen中的方法会在当前线程执行
     */
    public static LoadBitmapTask getBitmapInBg(String url,BitmapLoadingListener listen){
        return new LoadBitmapTask(null, url, listen).execute();
    }
    /**
     * 异步获取图片，会立即返回。从listen中监听，listen中的方法会在当前线程执行
     */
    public static LoadBitmapTask getBitmapInBg(Context context, String url,BitmapLoadingListener listen){
        return new LoadBitmapTask(context, url, listen).execute();
    }
	/**
	 * 异步获取图片（如果已缓存，则同步执行）。从listen中监听，listen中的方法会在当前线程执行
	 * @param delay 延迟这个时间再载入
	 */
	private static LoadBitmapTask getBitmapInBg(Context context, String url,BitmapLoadingListener listen, int delay, LoadChecker checker){

        if(getImgFile(url).exists()){//如果文件已经存在本地了，同步载入
            try {
                Bitmap bitmap = getBitmap(url);
                if(bitmap!=null){
                    listen.onBitmapLoadFinish(bitmap, true);
                    return null;
                }
            } catch (Exception ignore) {
            }
        }

        return new LoadBitmapTask(context, url, listen,delay,checker).execute();
	}
	/**
	 * 获取图片，会堵塞当前线程
	 * @param url 图片地址
	 * @return 请求的图片（如果失败会返回loadingFailBitmap）
	 */
	public static Bitmap getBitmap(String url){
		return getBitmap(url, null);
	}
	/**
	 * 获取图片
	 * @param url 图片地址
	 * @param task 读取bitmap的一个task，用作监听进度
	 * @return bitmap
	 */
	private static Bitmap getBitmap(String url,LoadBitmapTask task){
		if(url==null){
			return Default_LoadingFailBitmap;
		}
		try {
			Bitmap bitmap = getFromBitmapList(url, task);
			if (bitmap == null) return Default_LoadingFailBitmap;
			return bitmap;
		} catch (Exception e) {
			e.printStackTrace();
			return Default_LoadingFailBitmap;
		}
	}
	/**
	 * 从内存图片缓存中获取图片（如果获取失败则从内存字节缓存中获取）
	 * @param url url 图片的地址（在内存缓存中作为key）
	 * @param task 读取bitmap的一个task，用作监听进度
	 * @return 图片的字节数组
	 */
	private static Bitmap getFromBitmapList(String url,LoadBitmapTask task){
		Bitmap bitmap=bitmapList.get(url);
		if(bitmap==null||bitmap.isRecycled()){//从内存图片缓存获取失败，则尝试从内存字节缓存获取
			byte[] img_bytes=getFromImageBytesList(url,task);
			if(img_bytes==null) return null;
			bitmap = BitmapFactory.decodeByteArray(img_bytes, 0, img_bytes.length);
            if(bitmap == null){//解析失败，删除图片文件和缓存
                imageBytesList.remove(url);
                if(!getImgFile(url).delete()){
                    getImgFile(url).deleteOnExit();
                }
                return null;
            }
			bitmap = scaleToMiniBitmap(bitmap);
			putToBitmapList(url, bitmap);
		}
		return bitmap;
	}

	/**
	 * 从内存字节缓存中获取图片（如果获取失败则从本地缓存(网络)中获取）
	 * @param url 图片的地址（在内存缓存中作为key）
	 * @param task 读取bitmap的一个task，用作监听进度
	 * @return 图片的字节数组
	 */
	private static byte[] getFromImageBytesList(String url,LoadBitmapTask task){
		byte[] img_bytes= imageBytesList.get(url);//尝试从内存缓存imagebytesList中获取
		if(img_bytes==null){//从imagebytesList获取失败则尝试从本地(网络)获取
            if(task!=null){
                //先确认是否已经在载入中
                LoadBitmapTask loadingTask = BitmapManager.loadingTasks.get(url);
                if(loadingTask != null){//listener的转移
//                loadingTask.listeners.clear();//清除原有的监听
                    loadingTask.listeners.addAll(task.listeners);
                    task.listeners.clear();
                    return null;
                }
                BitmapManager.loadingTasks.put(url, task);
            }

            img_bytes=getImgBytesInDisk(url,task);
            putToImageBytesList(url, img_bytes);

            BitmapManager.loadingTasks.remove(url);
		}
		return img_bytes;
	}
	public static File getImgFile(String urlStr){
		if(cacheImgDir==null) return null;
		String filename=convertToFileName(urlStr);
		return new File(cacheImgDir, filename);
	}

    private static ConcurrentHashMap<String, LoadBitmapTask> loadingTasks = new ConcurrentHashMap<String, LoadBitmapTask>();
	/**
	 * 从本地读取图片(如果本地不存在，则从网络获取)
	 * @param urlStr 图片的地址，会被转换成本地路径储存在imgDir目录里
	 * @param task 读取bitmap的一个task，用作监听进度
	 * @return 图片的字节数组
	 */
	private static byte[] getImgBytesInDisk(String urlStr,final LoadBitmapTask task){
		//尝试从本地文件获取图片
		final File imageFile = getImgFile(urlStr);
		if(cacheImgDir!=null){
			if (imageFile.exists() && imageFile.length() > 0) {
				try {
					byte[] bytes = getBytesFromInputStream(new BufferedInputStream(new FileInputStream(imageFile)));
					if (bytes == null){
                        deleteFile(imageFile);
					}else return bytes;
				} catch (Exception e) {
					e.printStackTrace();
                    deleteFile(imageFile);//删除错误（写入一半）的文件
				}
			}
		}

		//没有从本地文件获取到，那么尝试从网络获取(等待延迟的载入)
		if(task!=null && task.delay>0){
			try {
				Thread.sleep(task.delay);
				if(task.checker!=null && !task.checker.canLoad()) return null;
			} catch (InterruptedException e) {
				e.printStackTrace();
				return null;
			}
		}

        File dlFile = HttpUtils.reqForDownload(urlStr, imageFile, false, new HttpUtils.DownloadListener() {
            @Override
            public void onDownloadFinish(File file) {
            }
            @Override
            public void onDownloading(long loaded, long total) {
                if(task!=null) task.pushProgress((int) (loaded*100/total));
            }
            @Override
            public void onDownloadError(String error) {
            }
        });
        if(dlFile==null){
            deleteFile(imageFile);
            return null;
        }
        try {

            FileInputStream fis = new FileInputStream(imageFile);
            return getBytesFromInputStream(fis);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
	}
	/**
	 * 从输入流读取图像数据
	 * @param is 输入流，文件输入流或网络输入流
	 * @return 字节数组
	 */
	private static byte[] getBytesFromInputStream(InputStream is){
		ByteArrayOutputStream baos=null;
		try {
			baos = new ByteArrayOutputStream();
			byte[] bytes = new byte[8 * 1024];
			int tempLength;
			while ((tempLength = is.read(bytes)) != -1) {
				if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
				baos.write(bytes, 0, tempLength);
			}
			byte[] imgbytes = baos.toByteArray();
			baos.close();
			is.close();
			return imgbytes;
		} catch (Exception e) {
			e.printStackTrace();
			try {
				if (baos != null) baos.close();
			} catch (Exception ignore) {
			}
			try {
				is.close();
			} catch (IOException ignore) {
			}
			return null;
		}
	}
	/**
	 * 将url转化为文件名储存
	 */
	private static String convertToFileName(String urlStr){
		try {
            String path = URI.create(urlStr).getPath();
			return urlStr.hashCode()+"."+new File(path).getName()+ImgFileNameExtension;
		} catch (Exception e) {
			e.printStackTrace();
		}


		return urlStr.hashCode()+"."+ImgFileNameExtension;
	}
	private static void putToBitmapList(String url,Bitmap bitmap){
		if(!TextUtils.isEmpty(url) && bitmap!=null){
            if(DEBUG) Log.w(BitmapManager.class.getSimpleName(),bitmapList.size()+"/"+bitmapList.maxSize()+
                    ",put size:"+bitmap.getRowBytes() * bitmap.getHeight()+",url:"+url);
			bitmapList.put(url, bitmap);
		}
	}
	/**
	 * 检查缓存大小是否超出，如果超出则自动清除最早的缓存
	 */
	private static void putToImageBytesList(String url,byte[] bytes){
		if(!TextUtils.isEmpty(url) && bytes!=null &&bytes.length>0){
			imageBytesList.put(url, bytes);
		}
	}

	private static Bitmap scaleToMiniBitmap(Bitmap in){
		return scaleToMiniBitmap(in, MAX_Bitmap_Width, MAX_Bitmap_Height);
	}
	//将图片控制在限定高宽之内
	public static Bitmap scaleToMiniBitmap(Bitmap in,int widthLimit,int heightLimit){
		int inWidth=in.getWidth();
		int inHeight=in.getHeight();
		if(inWidth<=widthLimit && inHeight<=heightLimit) return in;
		float scale=Math.min(widthLimit*1f/inWidth,heightLimit*1f/inHeight);
		Bitmap re=Bitmap.createScaledBitmap(in, (int)(inWidth*scale), (int) (inHeight*scale), true);
		in.recycle();
		return re;
	}
	/**
	 * 压缩Bitmap，返回压缩后的字节数组
	 * @param bitmap 目标Bitmap
	 * @param quality 压缩质量
	 * @param isRecycle 压缩完成后是否回收Bitmap
	 * @return 压缩的字节
	 */
	public static byte[] compressBitmap(Bitmap bitmap,int quality,boolean isRecycle){
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		bitmap.compress(CompressFormat.JPEG, quality, baos);
		if(isRecycle) bitmap.recycle();
		try {
			baos.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return baos.toByteArray();
	}
    private static void deleteFile(File imageFile){
        imageFile.delete();
    }
	/**
	 * 图像载入进度的监听接口
	 * @author linfaxin
	 */
	public interface BitmapLoadingListener{
		/**
		 * 载入结束
		 * @param bitmap 载入的最终图片（有可能是loadingFailBitmap）
		 * @param isLoadSuccess 载入是否失败
		 */
		public void onBitmapLoadFinish(Bitmap bitmap, boolean isLoadSuccess);
		/**
		 * @param progress 载入的进度。（0-100）
		 */
		public void onBitmapLoading(int progress);
	}
	/**载入的检查器（判断是否可以载入） */
	public interface LoadChecker{
		public boolean canLoad();
	}
	/**
	 * 为了让listener的载入结果、进度在主线程上运行的类
	 * @author linfaxin
	 */
	public static class LoadBitmapTask extends ActivityAsyncTask<Bitmap> {
		ArrayList<BitmapLoadingListener> listeners = new ArrayList<BitmapLoadingListener>();
		int delay;
		LoadChecker checker;
        String url;
        public LoadBitmapTask(Context context, String url, BitmapLoadingListener bitmapLoadingListener){
            super(context);
            this.url = url;
            listeners.add(bitmapLoadingListener);
		}
		public LoadBitmapTask(Context context, String url, BitmapLoadingListener bitmapLoadingListener,int delay,LoadChecker checker){
            super(context);
            this.url = url;
            listeners.add(bitmapLoadingListener);
			this.delay=delay;
			this.checker=checker;
		}
		@Override
		protected Bitmap doInBackground(Void... params) {
			try {
				Thread.sleep(200);//延迟载入，有效防止列表中快速滚动时的性能
				if(checker!=null && !checker.canLoad()) return null;
			} catch (InterruptedException e) {
				return null;
			}
			return getBitmap(url, this);
		}

		private int lastProgress=0;
		private void pushProgress(int progress){
			if(lastProgress!=progress){
                lastProgress=progress;
				this.publishProgress();
			}
		}
		@Override
		protected void onProgressUpdate(Void... values) {
            for(BitmapLoadingListener listener: listeners){
                listener.onBitmapLoading(lastProgress);
            }
		}
		@Override
		protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
			if(checker!=null && !checker.canLoad()) bitmap=null;
            for(BitmapLoadingListener listener: listeners){
                listener.onBitmapLoadFinish(bitmap, bitmap != Default_LoadingFailBitmap);
            }
		}
        @SuppressLint("NewApi")
        public LoadBitmapTask execute(){
            if(android.os.Build.VERSION.SDK_INT<11) super.execute();
            else executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

            return this;
        }
		
	}
}
