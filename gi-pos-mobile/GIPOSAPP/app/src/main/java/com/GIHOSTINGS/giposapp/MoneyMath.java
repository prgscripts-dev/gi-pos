package com.GIHOSTINGS.giposapp;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyMath {
  private MoneyMath() {}
  public static long toMinor(double value){return BigDecimal.valueOf(value).setScale(2,RoundingMode.HALF_UP).movePointRight(2).longValueExact();}
  public static double fromMinor(long value){return BigDecimal.valueOf(value,2).doubleValue();}
  public static double round(double value){return fromMinor(toMinor(value));}
  public static long percentOf(long amount,double percent){if(percent<=0)return 0;BigDecimal result=BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100),0,RoundingMode.HALF_UP);return Math.min(amount,Math.max(0,result.longValue()));}
  public static long inclusiveTax(long amount,double rate){if(amount<=0||rate<=0)return 0;BigDecimal result=BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(rate)).divide(BigDecimal.valueOf(100).add(BigDecimal.valueOf(rate)),0,RoundingMode.HALF_UP);return result.longValue();}
  public static long exclusiveTax(long amount,double rate){if(amount<=0||rate<=0)return 0;return percentOf(amount,rate);}
}
