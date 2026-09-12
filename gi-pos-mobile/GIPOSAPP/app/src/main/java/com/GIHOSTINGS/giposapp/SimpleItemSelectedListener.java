package com.GIHOSTINGS.giposapp;

import android.view.View;
import android.widget.AdapterView;

public final class SimpleItemSelectedListener implements AdapterView.OnItemSelectedListener {
  public interface Selection { void selected(int position); }
  private final Selection selection;
  public SimpleItemSelectedListener(Selection selection){this.selection=selection;}
  @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){selection.selected(position);}
  @Override public void onNothingSelected(AdapterView<?> parent){}
}
