package com.drone.rcn1cbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.regex.*;

public final class UpdateActivity extends Activity {
    private static final String API="https://api.github.com/repos/iTzFrancesco/dji-rcn1c-flight-deck/releases/latest";
    private static final String TRUST="https://github.com/iTzFrancesco/dji-rcn1c-flight-deck/";
    private TextView status; private Button action; private String remoteVersion, remoteUrl;

    @Override protected void onCreate(Bundle b){ super.onCreate(b); buildUi(); check(); }
    private void buildUi(){
        LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL); r.setGravity(Gravity.CENTER); r.setPadding(dp(24),dp(18),dp(24),dp(18)); r.setBackgroundColor(0xFF0B0F14);
        TextView t=txt("AGGIORNAMENTI",20,0xFFEAF2FF,true); t.setGravity(Gravity.CENTER); r.addView(t);
        TextView v=txt("Installata: v"+BuildConfig.VERSION_NAME,12,0xFF8294A8,false); v.setGravity(Gravity.CENTER); v.setPadding(0,dp(7),0,dp(16)); r.addView(v);
        status=txt("Controllo GitHub…",12,0xFF92A4B8,false); status.setGravity(Gravity.CENTER); r.addView(status,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));
        action=btn("CONTROLLO…",0xFF123B32,0xFF2ED573); action.setEnabled(false); action.setOnClickListener(x->{ if(remoteUrl!=null) download(); else check(); }); r.addView(action,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)));
        Button back=btn("INDIETRO",0xFF171E28,0xFF52657A); back.setOnClickListener(x->finish()); LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(42)); bp.topMargin=dp(8); r.addView(back,bp); setContentView(r);
    }
    private void check(){ remoteUrl=null; action.setEnabled(false); action.setText("CONTROLLO…"); status.setText("Controllo ultima release…"); new Thread(()->{ HttpURLConnection c=null; try{ c=(HttpURLConnection)new URL(API).openConnection(); c.setConnectTimeout(12000); c.setReadTimeout(12000); c.setRequestProperty("Accept","application/vnd.github+json"); c.setRequestProperty("User-Agent","RCN1C-Flight-Bridge/"+BuildConfig.VERSION_NAME); if(c.getResponseCode()!=200)throw new IOException("GitHub HTTP "+c.getResponseCode()); String j=read(c.getInputStream()); String tag=value(j,"\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""); String url=value(j,"\\\"name\\\"\\s*:\\s*\\\"RCN1C_Bridge\\\\.apk\\\".*?\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""); if(tag==null||url==null||!url.startsWith(TRUST))throw new IOException("APK release non trovata"); String rv=tag.startsWith("v")?tag.substring(1):tag; runOnUiThread(()->{ if(compare(rv,BuildConfig.VERSION_NAME)>0){ remoteVersion=rv; remoteUrl=url; status.setText("Nuova versione: v"+rv); action.setText("SCARICA E AGGIORNA"); } else { status.setText("Sei già aggiornato · v"+BuildConfig.VERSION_NAME); action.setText("RICONTROLLA"); } action.setEnabled(true); }); }catch(Exception e){ runOnUiThread(()->{status.setText("Controllo fallito: "+e.getMessage()); action.setText("RIPROVA"); action.setEnabled(true);}); }finally{if(c!=null)c.disconnect();}},"update-check").start(); }
    private void download(){ action.setEnabled(false); status.setText("Scarico v"+remoteVersion+"…"); final String url=remoteUrl, ver=remoteVersion; new Thread(()->{ HttpURLConnection c=null; try{ File d=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS); if(d==null)throw new IOException("Download non disponibile"); d.mkdirs(); File apk=new File(d,"RCN1C_Bridge-"+ver+".apk"); c=(HttpURLConnection)new URL(url).openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true); if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode()); try(InputStream in=new BufferedInputStream(c.getInputStream()); FileOutputStream out=new FileOutputStream(apk)){ byte[] buf=new byte[8192]; for(int n;(n=in.read(buf))!=-1;)out.write(buf,0,n); } if(apk.length()<10000)throw new IOException("APK non valida"); if(!sameSigner(apk))throw new SecurityException("firma APK diversa: aggiornamento bloccato"); ApkFileProvider.setSharedFile(apk); Uri u=ApkFileProvider.uriFor(apk); Intent i=new Intent(Intent.ACTION_VIEW); i.setDataAndType(u,"application/vnd.android.package-archive"); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); i.setClipData(ClipData.newRawUri("RCN1C_Bridge",u)); runOnUiThread(()->{status.setText("APK verificata · conferma l'installazione"); action.setEnabled(true); try{startActivity(i);}catch(ActivityNotFoundException e){toast("Installatore APK non disponibile");}}); }catch(Exception e){runOnUiThread(()->{status.setText("Aggiornamento fermato: "+e.getMessage()); action.setEnabled(true);});}finally{if(c!=null)c.disconnect();}},"update-download").start(); }
    private boolean sameSigner(File apk)throws Exception{ PackageManager pm=getPackageManager(); PackageInfo cur, arc; if(Build.VERSION.SDK_INT>=28){ cur=pm.getPackageInfo(getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES); arc=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES); if(arc==null||arc.signingInfo==null)return false; return digest(cur.signingInfo.getApkContentsSigners()[0]).equals(digest(arc.signingInfo.getApkContentsSigners()[0])); } cur=pm.getPackageInfo(getPackageName(),PackageManager.GET_SIGNATURES); arc=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNATURES); return arc!=null&&cur.signatures!=null&&arc.signatures!=null&&digest(cur.signatures[0]).equals(digest(arc.signatures[0])); }
    private String digest(Signature s)throws Exception{ byte[] d=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()); StringBuilder b=new StringBuilder(); for(byte x:d)b.append(String.format("%02x",x)); return b.toString(); }
    private static int compare(String l,String r){ String[] la=l.split("-",2),ra=r.split("-",2); String[] a=la[0].split("\\."),b=ra[0].split("\\."); for(int i=0;i<Math.max(a.length,b.length);i++){int x=i<a.length?Integer.parseInt(a[i]):0,y=i<b.length?Integer.parseInt(b[i]):0;if(x!=y)return x<y?-1:1;} if(la.length==1&&ra.length>1)return 1;if(la.length>1&&ra.length==1)return -1;if(la.length==1)return 0; Matcher lm=Pattern.compile("(\\d+)").matcher(la[1]),rm=Pattern.compile("(\\d+)").matcher(ra[1]); int x=lm.find()?Integer.parseInt(lm.group(1)):0,y=rm.find()?Integer.parseInt(rm.group(1)):0; return x==y?la[1].compareToIgnoreCase(ra[1]):(x<y?-1:1); }
    private static String read(InputStream i)throws IOException{try(InputStream in=i;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[]b=new byte[4096];for(int n;(n=in.read(b))!=-1;)o.write(b,0,n);return new String(o.toByteArray(),"UTF-8");}}
    private static String value(String j,String e){Matcher m=Pattern.compile(e,Pattern.DOTALL).matcher(j);return m.find()?m.group(1).replace("\\/","/"):null;}
    private TextView txt(String s,int z,int c,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setTextColor(c);if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
    private Button btn(String s,int fill,int stroke){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(0xFFEAF2FF);b.setStateListAnimator(null);GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(12));g.setStroke(dp(1),stroke);b.setBackground(g);return b;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();} private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
