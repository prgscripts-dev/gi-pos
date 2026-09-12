package com.GIHOSTINGS.giposapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.util.LruCache;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ItemImageLoader {
  private static final ExecutorService workers=Executors.newFixedThreadPool(2);
  private static final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(12*1024){
    @Override protected int sizeOf(String key,Bitmap value){return value.getByteCount()/1024;}
  };

  private ItemImageLoader(){}

  static void load(Context context,ImageView view,String source,int targetPx){
    view.setImageDrawable(null);
    if(source==null||source.isBlank()){
      view.setTag(null);
      view.setVisibility(View.GONE);
      return;
    }
    view.setTag(source);
    view.setVisibility(View.VISIBLE);
    Bitmap ready;
    synchronized(cache){ready=cache.get(source);}
    if(ready!=null){view.setImageBitmap(ready);return;}
    Context app=context.getApplicationContext();
    workers.execute(()->{
      Bitmap decoded=decode(app,source,targetPx);
      if(decoded!=null){synchronized(cache){cache.put(source,decoded);}}
      view.post(()->{
        if(!source.equals(view.getTag()))return;
        if(decoded==null){view.setImageDrawable(null);view.setVisibility(View.GONE);}
        else{view.setVisibility(View.VISIBLE);view.setImageBitmap(decoded);}
      });
    });
  }

  private static Bitmap decode(Context context,String source,int targetPx){
    try{
      Uri uri=Uri.parse(source);
      BitmapFactory.Options bounds=new BitmapFactory.Options();
      bounds.inJustDecodeBounds=true;
      try(InputStream input=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(input,null,bounds);}
      int sample=1;
      int largest=Math.max(bounds.outWidth,bounds.outHeight);
      while(largest/sample>targetPx*2)sample*=2;
      BitmapFactory.Options options=new BitmapFactory.Options();
      options.inSampleSize=Math.max(1,sample);
      options.inPreferredConfig=Bitmap.Config.RGB_565;
      try(InputStream input=context.getContentResolver().openInputStream(uri)){return BitmapFactory.decodeStream(input,null,options);}
    }catch(Exception ignored){return null;}
  }
}
