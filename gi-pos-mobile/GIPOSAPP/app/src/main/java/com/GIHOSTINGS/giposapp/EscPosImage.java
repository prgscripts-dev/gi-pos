package com.GIHOSTINGS.giposapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Converts a business logo image into ESC/POS raster bit-image bytes so it can
 * be printed at the top of a receipt on a thermal printer.
 *
 * <p>The logo is decoded, scaled to fit the printer's dot width (58mm ~ 384
 * dots, 80mm ~ 576 dots), converted to 1-bit monochrome using a luminance
 * threshold, and packed into the GS v 0 raster format. Everything is
 * best-effort: any failure returns an empty byte array so a bad logo never
 * blocks a print.
 */
public final class EscPosImage {
  private EscPosImage(){}

  // Full printable dot width for common thermal paper sizes.
  private static int dotWidth(int paperWidthMm){return paperWidthMm<=58?384:576;}
  // A small, neat logo: a compact emblem (~28% of paper width) centered at the
  // top of the bill, not a full-width banner.
  private static int logoWidth(int paperWidthMm){return paperWidthMm<=58?104:160;}

  /** ESC/POS bytes for a small centered logo, or empty if none/failure. */
  public static byte[] logoBytes(Context context,String logoUri,int paperWidthMm){
    if(logoUri==null||logoUri.trim().isEmpty())return new byte[0];
    Bitmap bitmap=null;
    try{
      bitmap=decode(context,Uri.parse(logoUri),logoWidth(paperWidthMm));
      if(bitmap==null)return new byte[0];
      return raster(bitmap,dotWidth(paperWidthMm));
    }catch(Throwable ignored){
      return new byte[0];
    }finally{
      if(bitmap!=null)bitmap.recycle();
    }
  }

  private static Bitmap decode(Context context,Uri uri,int maxWidth)throws Exception{
    // First pass: read bounds to compute a sane sample size.
    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
    try(InputStream in=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}
    if(bounds.outWidth<=0)return null;
    int sample=1;while(bounds.outWidth/(sample*2)>=maxWidth)sample*=2;
    BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=sample;
    Bitmap raw;
    try(InputStream in=context.getContentResolver().openInputStream(uri)){raw=BitmapFactory.decodeStream(in,null,opts);}
    if(raw==null)return null;
    // Scale so width fits the small logo box; also cap the height so a tall
    // image stays compact.
    int targetW=Math.min(maxWidth,raw.getWidth());
    if(targetW<8)targetW=Math.min(maxWidth,raw.getWidth());
    int targetH=Math.max(1,Math.round(raw.getHeight()*(targetW/(float)raw.getWidth())));
    int maxHeight=maxWidth; // keep it roughly square at most
    if(targetH>maxHeight){float k=maxHeight/(float)targetH;targetH=maxHeight;targetW=Math.max(1,Math.round(targetW*k));}
    // Round width to a multiple of 8 dots so the printer centers a whole byte
    // block (prevents the logo drifting a few dots to one side).
    targetW=Math.max(8,(targetW/8)*8);
    Bitmap scaled=Bitmap.createScaledBitmap(raw,targetW,targetH,true);
    if(scaled!=raw)raw.recycle();
    return scaled;
  }

  // Pack a bitmap into GS v 0 raster format, centered within the paper width.
  private static byte[] raster(Bitmap bitmap,int dotWidth){
    int w=bitmap.getWidth(),h=bitmap.getHeight();
    // Emit the image at its natural byte-width and let the printer's center
    // alignment (ESC a 1) position it. Byte-align the width to a multiple of 8
    // so the printer centers a clean block (no internal padding that would
    // fight the printer's own centering and push it off to one side).
    int bytesPerRow=(w+7)/8;
    byte[] pixels=new byte[bytesPerRow*h];
    for(int y=0;y<h;y++){
      for(int x=0;x<w;x++){
        int px=bitmap.getPixel(x,y);
        int a=Color.alpha(px);
        // Treat transparent as white; otherwise threshold on luminance.
        int lum=a<128?255:(int)(0.299*Color.red(px)+0.587*Color.green(px)+0.114*Color.blue(px));
        if(lum<128){ // dark pixel -> print (bit set)
          pixels[y*bytesPerRow+(x/8)]|=(byte)(0x80>>(x%8));
        }
      }
    }
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    // GS v 0 m xL xH yL yH  (m=0 normal)
    out.write(0x1D);out.write(0x76);out.write(0x30);out.write(0);
    out.write(bytesPerRow&0xFF);out.write((bytesPerRow>>8)&0xFF);
    out.write(h&0xFF);out.write((h>>8)&0xFF);
    out.write(pixels,0,pixels.length);
    out.write('\n');
    return out.toByteArray();
  }
}
