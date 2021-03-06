package io.dcloud.share.mm;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import com.tencent.mm.sdk.modelbase.BaseResp;
import com.tencent.mm.sdk.modelmsg.SendAuth;
import com.tencent.mm.sdk.modelmsg.SendMessageToWX;
import com.tencent.mm.sdk.modelmsg.WXImageObject;
import com.tencent.mm.sdk.modelmsg.WXMediaMessage;
import com.tencent.mm.sdk.modelmsg.WXTextObject;
import com.tencent.mm.sdk.modelmsg.WXWebpageObject;
import com.tencent.mm.sdk.openapi.IWXAPI;
import com.tencent.mm.sdk.openapi.WXAPIFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;

import io.dcloud.ProcessMediator;
import io.dcloud.RInformation;
import io.dcloud.common.DHInterface.FeatureMessageDispatcher;
import io.dcloud.common.DHInterface.FeatureMessageDispatcher.MessageListener;
import io.dcloud.common.DHInterface.IActivityHandler;
import io.dcloud.common.DHInterface.ISysEventListener;
import io.dcloud.common.DHInterface.IWebview;
import io.dcloud.common.adapter.util.AndroidResources;
import io.dcloud.common.adapter.util.Logger;
import io.dcloud.common.adapter.util.PlatformUtil;
import io.dcloud.common.constant.DOMException;
import io.dcloud.common.constant.StringConst;
import io.dcloud.common.util.JSONUtil;
import io.dcloud.common.util.JSUtil;
import io.dcloud.common.util.PdrUtil;
import io.dcloud.share.IFShareApi;

/**
 * <p>Description:微信api管理者</p>
 *
 * @version 1.0
 * @author cuidengfeng Email:cuidengfeng@dcloud.io
 * @Date 2013-6-13 下午3:09:14 created.
 * 
 * <pre><p>ModifiedLog:</p>
 * Log ID: 1.0 (Log编号 依次递增)
 * Modified By: cuidengfeng Email:cuidengfeng@dcloud.io at 2013-6-13 下午3:09:14</pre>
 */
public class WeiXinApiManager implements IFShareApi{

	private static final String WEIXIN_DES = "微信";
	public static final String WEIXIN_ID = "weixin";
	public static final String KEY_APPID = "appid";
	public static final int THUMB_SIZE = 150;
    private static final String TAG = "WeiXinApiManager";
    private IWXAPI api;
	private static String APPID;
	
	@Override
	public void initConfig() {
		initData();
	}
	public void initData(){
//		APPID = "wxd930ea5d5a258f4f";//wx489313a817400fa0 demo——appid : wxd930ea5d5a258f4f  pname : net.sourceforge.simcpux
		APPID = AndroidResources.getMetaValue("WX_APPID");//AndroidManifest.xml中配置多个相同meta-data数据时使用后边的
	}

	private boolean hasFullConfigData(){
		return !TextUtils.isEmpty(APPID);
	}
	//返回false时则继续
	private boolean hasGeneralError(IWebview pWebViewImpl,String pCallbackId){
		if(!hasFullConfigData()){
			String msg = String.format(DOMException.JSON_ERROR_INFO, DOMException.CODE_BUSINESS_PARAMETER_HAS_NOT, DOMException.toString(DOMException.MSG_BUSINESS_PARAMETER_HAS_NOT));
			JSUtil.execCallback(pWebViewImpl, pCallbackId, msg, JSUtil.ERROR, true, false);
			return true;
		}else if(!PlatformUtil.hasAppInstalled(pWebViewImpl.getContext(), "com.tencent.mm")){
			String msg = String.format(DOMException.JSON_ERROR_INFO, DOMException.CODE_CLIENT_UNINSTALLED, DOMException.toString(DOMException.MSG_CLIENT_UNINSTALLED));
			JSUtil.execCallback(pWebViewImpl, pCallbackId, msg, JSUtil.ERROR, true, false);
			return true;
		}
		return false;
	}
	@Override
	public String getId() {
		return WEIXIN_ID;
	}

	Object[] sendCallbackMsg = null;
	private void registerSendCallbackMsg(Object[] args){
		sendCallbackMsg = args;
	}
	void executeSendCallbackMsg(BaseResp resp){
		if(sendCallbackMsg != null){
			IWebview pWebViewImpl = (IWebview)sendCallbackMsg[0];
			String pCallbackId = (String)sendCallbackMsg[1];
			if(resp != null){
				onSendCallBack(pWebViewImpl, pCallbackId, resp.errCode);
			}
		}
	}
	MessageListener sSendCallbackMessageListener = new MessageListener() {
		
		@Override
		public void onReceiver(Object msg) {
			if(msg instanceof BaseResp){
				executeSendCallbackMsg((BaseResp)msg);
				FeatureMessageDispatcher.unregisterListener(sSendCallbackMessageListener);
			}
		}
	};
//	@Override
	public void send(final IWebview pWebViewImpl,final String pCallbackId,final String pShareMsg) {
			if(hasGeneralError(pWebViewImpl, pCallbackId)){
				return;
			}
			new Thread(){
				public void run() {
					try {
						JSONObject _msg = new JSONObject(pShareMsg);
						String _content = _msg.optString("content");
						String _title = _msg.optString("title");
						String href = JSONUtil.getString(_msg, "href") ;
						JSONArray _thumbs = _msg.optJSONArray("thumbs");
						JSONArray _pictures = _msg.optJSONArray("pictures");
						JSONObject extraInfo = JSONUtil.getJSONObject(_msg, "extra");
						SendMessageToWX.Req req = new SendMessageToWX.Req();
						req.scene = SendMessageToWX.Req.WXSceneTimeline;//默认分享到朋友圈
						//是否同步到朋友圈WXSceneTimeline 默认为不同步WXSceneSession，只有4.2以上的微信客户端才支持同步朋友圈
						if(extraInfo != null && !extraInfo.isNull("scene")){
							String senceValue = JSONUtil.getString(extraInfo, "scene");
							if("WXSceneSession".equals(senceValue)){
								req.scene = SendMessageToWX.Req.WXSceneSession;//会话
							}
							else if("WXSceneFavorite".equals(senceValue)){
								req.scene = SendMessageToWX.Req.WXSceneFavorite;//收藏
							}
						}
						boolean isContinue = false;
						try {
							if(!PdrUtil.isEmpty(href)){
								reqWebPageMsg(pWebViewImpl,req,href, _thumbs != null ? _thumbs.optString(0,null) : null, _content, _title);
							}else if(_pictures != null && _pictures.length() > 0){
								isContinue = reqImageMsg(pWebViewImpl,req, _pictures, _thumbs,_title);
							}else {
								reqTextMsg(req,_content,_title);
							}
							isContinue = true;
						} catch (Exception e) {//如遇到异常需要执行错误回调
							e.printStackTrace();
						}
						if(!isContinue) {
							pWebViewImpl.obtainWebview().postDelayed(new Runnable(){
								@Override
								public void run() {
									onSendCallBack(pWebViewImpl, pCallbackId, BaseResp.ErrCode.ERR_OK);
								}
							}, 500);
							return;
						}
						if(pWebViewImpl.getActivity() instanceof IActivityHandler && ((IActivityHandler)pWebViewImpl.getActivity()).isMultiProcessMode()){//多进程模式
							startWeiXinMediator(pWebViewImpl,pCallbackId,req);
							return;
						}
						final boolean suc = api.sendReq(req);
						if(suc && hasWXEntryActivity(pWebViewImpl.getContext())){
							FeatureMessageDispatcher.registerListener(sSendCallbackMessageListener);
							registerSendCallbackMsg(new Object[]{pWebViewImpl, pCallbackId});
						}else{
							pWebViewImpl.obtainWebview().postDelayed(new Runnable(){
								@Override
								public void run() {
									onSendCallBack(pWebViewImpl, pCallbackId, BaseResp.ErrCode.ERR_SENT_FAILED);
								}
							}, 500);
						}
					} catch (JSONException e) {
						//code ShareApiManager.SHARE_CONTENT_ERROR
					}
				};
			}.start();
			
	}

	private void startWeiXinMediator(final IWebview pWebViewImpl,final String pCallbackId,SendMessageToWX.Req req) {
		Intent intent = new Intent();
		intent.putExtra(ProcessMediator.LOGIC_CLASS, WeiXinMediator.class.getName());
		Bundle bundle = new Bundle();
		req.toBundle(bundle);
		intent.putExtra(ProcessMediator.REQ_DATA,bundle);
		intent.setClassName(pWebViewImpl.getActivity(),ProcessMediator.class.getName());
		pWebViewImpl.getActivity().startActivityForResult(intent,ProcessMediator.CODE_REQUEST);
		pWebViewImpl.getActivity().overridePendingTransition(0,0);
		pWebViewImpl.obtainApp().registerSysEventListener(new ISysEventListener() {
			@Override
			public boolean onExecute(SysEventType pEventType, Object pArgs) {
				Object[] _args = (Object[])pArgs;
				int requestCode = (Integer)_args[0];
				int resultCode = (Integer)_args[1];
				Intent data = (Intent) _args[2];
				if(pEventType == SysEventType.onActivityResult && requestCode == ProcessMediator.CODE_REQUEST){
					Bundle bundle = data.getBundleExtra(ProcessMediator.RESULT_DATA);
					if(bundle == null){
						onSendCallBack(pWebViewImpl, pCallbackId, BaseResp.ErrCode.ERR_SENT_FAILED);
					}else {
						String s = bundle.getString(ProcessMediator.STYLE_DATA);
						if ("BaseResp".equals(s)) {
							SendMessageToWX.Resp resp = new SendMessageToWX.Resp();
							resp.fromBundle(bundle);
							onSendCallBack(pWebViewImpl, pCallbackId, resp.errCode);
						} else if ("BaseReq".equals(s)) {
						}
					}
				}
				return false;
			}
		}, ISysEventListener.SysEventType.onActivityResult);
	}

	/**
	 * 供原生代码分享调用
	 * @param activity
	 * @param msg
	 * flag 1是朋友圈，0是好友
	 */
	public void send(final Activity activity, final String msg) {
		try {
			JSONObject msgJs = new JSONObject(msg);
			String content = msgJs.getString("content");
			String href = msgJs.getString("href");
			String thumbs = msgJs.getString("thumbs");
			int flag = msgJs.getInt("flag");
			initData();
			if(api == null){
				api = WXAPIFactory.createWXAPI(activity.getApplicationContext(), APPID, true);
			}
			api.registerApp(APPID);
			if (!api.isWXAppInstalled()) {  
		        Toast.makeText(activity.getApplicationContext(), "您还未安装微信客户端", Toast.LENGTH_SHORT).show();
		        return;  
		    } 
			WXWebpageObject webpage = new WXWebpageObject();
			webpage.webpageUrl = href;
			WXMediaMessage wxmsg = new WXMediaMessage(webpage); 
			wxmsg.description = content;  
			wxmsg.title = content;
		    Bitmap thumb = BitmapFactory.decodeFile(thumbs);
			//如果图标为null，默认用App的图标。
			if(null==thumb){
				thumb=BitmapFactory.decodeResource(activity.getResources(),RInformation.DRAWABLE_ICON);
			}
		    wxmsg.setThumbImage(thumb);
		    SendMessageToWX.Req req = new SendMessageToWX.Req();  
		    req.transaction = String.valueOf(System.currentTimeMillis());  
		    req.message = wxmsg;  
		    req.scene = flag;  
		    api.sendReq(req);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return;
		}
	}
	private void onSendCallBack(final IWebview pWebViewImpl,
			final String pCallbackId, int code) {
		boolean suc = false;
		String errorMsg = DOMException.MSG_SHARE_SEND_ERROR;
		if(code == BaseResp.ErrCode.ERR_OK){
			suc = true;
		}else if(code == BaseResp.ErrCode.ERR_AUTH_DENIED){
			errorMsg = "Authentication failed";
		}else if(code == BaseResp.ErrCode.ERR_COMM){
			errorMsg = "General errors";
		}else if(code == BaseResp.ErrCode.ERR_SENT_FAILED){
			errorMsg = "Unable to send";
		}else if(code == BaseResp.ErrCode.ERR_UNSUPPORT){
			errorMsg = "Unsupport error";
		}else if(code == BaseResp.ErrCode.ERR_USER_CANCEL){
			errorMsg = "User canceled";
		}
		if(suc){//由于调用微信发送接口，会立马回复true，而不是真正分享成功，甚至连微信界面都没有启动，在此延迟回调，以增强体验
			JSUtil.execCallback(pWebViewImpl, pCallbackId, "", JSUtil.OK, false, false);
		}else{
			String msg = String.format(DOMException.JSON_ERROR_INFO, DOMException.CODE_BUSINESS_INTERNAL_ERROR, DOMException.toString(code, "Share微信分享", errorMsg, mLink));
			JSUtil.execCallback(pWebViewImpl, pCallbackId, msg, JSUtil.ERROR, true, false);
		}
	}
	private boolean reqWebPageMsg(IWebview pWebViewImpl,SendMessageToWX.Req req,String webPage,String pThumbImg,String pText,String pTitle){
		WXWebpageObject webpage = new WXWebpageObject();
		webpage.webpageUrl = webPage;
		WXMediaMessage msg = new WXMediaMessage(webpage);
		if(!PdrUtil.isEmpty(pTitle)){//The length should be within 512Bytes
			msg.title = pTitle;
		} else if (req.scene == SendMessageToWX.Req.WXSceneTimeline && !TextUtils.isEmpty(pText)) {
			// 如果为朋友圈分享 title为空 则使用_content未标题
			msg.title = pText;
		}
		msg.description = pText;//The length should be within 1KB
		if (!PdrUtil.isEmpty(pThumbImg)) {
			msg.thumbData = buildThumbData(pWebViewImpl, pThumbImg);
		}
		req.transaction = buildTransaction("webpage");
		req.message = msg;
		return true;
	}
	private boolean reqTextMsg(SendMessageToWX.Req req,String pText,String pTitle){

		// 初始化一个WXTextObject对象
		WXTextObject textObj = new WXTextObject();
		textObj.text = pText;

		// 用WXTextObject对象初始化一个WXMediaMessage对象
		WXMediaMessage msg = new WXMediaMessage();
		msg.mediaObject = textObj;
		if(!PdrUtil.isEmpty(pTitle)){
			msg.title = pTitle;
		} else if (req.scene == SendMessageToWX.Req.WXSceneTimeline && !TextUtils.isEmpty(pText)) {
			// 如果为朋友圈分享 title为空 则使用_content未标题
			msg.title = pText;
		}
		msg.description = pText;
		
		// 构造一个Req
		req.transaction = buildTransaction("text"); // transaction字段用于唯一标识一个请求
		req.message = msg;
		
		return true;
	}
	private String buildTransaction(final String type) {
		return (type == null) ? String.valueOf(System.currentTimeMillis()) : type + System.currentTimeMillis();
	}
	
	private static Bitmap cpBitmap(Bitmap orgBitmap){
		if (PdrUtil.isEmpty(orgBitmap)) {
			return null;
		}
	    Bitmap tmp;
	    while(orgBitmap.getHeight()*orgBitmap.getRowBytes()>=32*1024){
	        tmp=Bitmap.createScaledBitmap(orgBitmap, orgBitmap.getWidth()*2/3, orgBitmap.getHeight()*2/3, true);
	        orgBitmap.recycle();
	        orgBitmap=tmp;
	    }
	    return orgBitmap;
	}
	
	private byte[] buildThumbData(IWebview pWebViewImpl,String thumeImgPath){
		byte[] ret = null;
		Bitmap bitmap = null;
		InputStream is = null;
		try {
//			The thumeImg size should be within 32KB * 1024 = 32768
			if(PdrUtil.isNetPath(thumeImgPath)){//是网络地址
				try {
					is = new URL(thumeImgPath).openStream();
				} catch (MalformedURLException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}else{
				is = pWebViewImpl.obtainFrameView().obtainApp().obtainResInStream(pWebViewImpl.obtainFullUrl(), thumeImgPath);
			}
		} catch (Exception e) {
			Logger.e("buildThumbData Exception=" + e);
		}
		if(is != null){
			bitmap = BitmapFactory.decodeStream(is);
		}
		if(bitmap == null){
			bitmap = BitmapFactory.decodeResource(pWebViewImpl.getActivity().getResources(), RInformation.DRAWABLE_ICON);
		}
		bitmap = cpBitmap(bitmap);
		ret = bmpToByteArray(bitmap, true);  // 设置缩略图
		return ret;
	}
	
	
	private void sedMultiplePic(Activity act,ArrayList<Uri> localArrayList,String activityClassName){
		Intent intent = new Intent();
		//com.tencent.mm.ui.chatting.ChattingUI
		//com.tencent.mm.plugin.favorite.ui.FavoriteIndexUI
		//com.tencent.mm.ui.tools.ShareToTimeLineUI
		ComponentName localComponentName = new ComponentName("com.tencent.mm", activityClassName);
		intent.setComponent(localComponentName);
		intent.setAction("android.intent.action.SEND_MULTIPLE");
		intent.setType("image/*");
		intent.putParcelableArrayListExtra("android.intent.extra.STREAM", localArrayList);
		act.startActivity(intent);
	}
	private boolean reqImageMsg(IWebview pWebViewImpl,SendMessageToWX.Req req,JSONArray pImgs, JSONArray pThumbImgs,String pTitle){
		WXMediaMessage msg = new WXMediaMessage();
		//判断个数，分享情形
		boolean mul = pImgs.length() > 1;
		mul = false;//暂只处理单个图片
		if(!mul){//单个图片
//			pImg 内容大小不超过10MB  https://open.weixin.qq.com/zh_CN/htmledition/res/dev/document/sdk/android/index.html
			String pImg = pImgs.optString(0);
			String pThumbImg = pThumbImgs == null || pThumbImgs.isNull(0) ? pImg : pThumbImgs.optString(0);
			if(PdrUtil.isNetPath(pImg)){
				WXImageObject imgObj = new WXImageObject();
                try {
                    Bitmap bmp = BitmapFactory.decodeStream(new URL(pImg).openStream());
                    imgObj.imageData = bmpToByteArray(bmp,true);//content size within 10MB.
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
				msg.mediaObject = imgObj;
				pThumbImg = PdrUtil.isEmpty( pThumbImg ) ? pImg:pThumbImg;
				msg.thumbData = buildThumbData(pWebViewImpl, pThumbImg);
			}else{//imagePath The length should be within 10KB and content size within 10MB.
				InputStream is = pWebViewImpl.obtainFrameView().obtainApp().obtainResInStream(pWebViewImpl.obtainFullUrl(), pImg);
				Bitmap bmp = BitmapFactory.decodeStream(is);
				WXImageObject imgObj = new WXImageObject(bmp);
				bmp.recycle();
//				WXImageObject imgObj = new WXImageObject();
//				imgObj.imagePath = pImg;//避免将来资源放置在程序私有目录第三方程序无权访问问题
				msg.mediaObject = imgObj;
				msg.thumbData = buildThumbData(pWebViewImpl, pThumbImg);
				
			}
		}else{
			String clssName = "com.tencent.mm.ui.tools.ShareToTimeLineUI";
			switch (req.scene) {
			case SendMessageToWX.Req.WXSceneSession:
				clssName = "com.tencent.mm.ui.chatting.ChattingUI";
				break;
			case SendMessageToWX.Req.WXSceneTimeline://朋友圈
				clssName = "com.tencent.mm.ui.tools.ShareToTimeLineUI";
				break;
			case SendMessageToWX.Req.WXSceneFavorite://收藏
				clssName = "com.tencent.mm.plugin.favorite.ui.FavoriteIndexUI";
				break;
			}
			ArrayList<Uri> localArrayList = new ArrayList<Uri>();
			for (int i = 0; i < pImgs.length(); i++) {
				String pic = pImgs.optString(i);
				if(PdrUtil.isNetPath(pic)){
					try {
						Bitmap bmp = BitmapFactory.decodeStream(new URL(pic).openStream());
						String t_path = pWebViewImpl.obtainApp().obtainAppTempPath() + System.currentTimeMillis() + ".png";
						PdrUtil.saveBitmapToFile(bmp,t_path );
						localArrayList.add(Uri.fromFile(new File(t_path)));
					} catch (MalformedURLException e) {
						e.printStackTrace();
					} catch (IOException e) {
						e.printStackTrace();
					}
//					localArrayList.add(Uri.parse(pic));
				}else{
					pic = pWebViewImpl.obtainApp().convert2AbsFullPath(pWebViewImpl.obtainFullUrl(),pic);
					localArrayList.add(Uri.fromFile(new File(pic)));
				}
//				String filePath = sdcardDir + "/1/" + pics[i];
			}
			sedMultiplePic(pWebViewImpl.getActivity(), localArrayList,clssName);
			return false;
		}
		
//		if(PdrUtil.isNetPath(pImg)){
//			WXImageObject imgObj = new WXImageObject();
//			imgObj.imageUrl = pImg;
//			msg.mediaObject = imgObj;
//			pThumbImg = PdrUtil.isEmpty( pThumbImg ) ? pImg:pThumbImg;
//			msg.thumbData = buildThumbData(pWebViewImpl, pThumbImg);
//		}else{
//			pImg = pWebViewImpl.obtainFrameView().obtainApp().convert2AbsFullPath(pWebViewImpl.obtainFullUrl(), pImg);
//			Bitmap bmp = BitmapFactory.decodeFile(pImg);
//			WXImageObject imgObj = new WXImageObject(bmp);
//			msg.mediaObject = imgObj;
//			msg.thumbData = buildThumbData(pWebViewImpl, pThumbImg);
//		}
		
		if(!PdrUtil.isEmpty(pTitle)){//The length should be within 512Bytes
			msg.title = pTitle;
		}
		req.transaction =buildTransaction("img");
		req.message = msg;
		return true;
	}
	
	public static byte[] bmpToByteArray(final Bitmap bmp, final boolean needRecycle) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		bmp.compress(CompressFormat.PNG, 100, output);
		if (needRecycle) {
			bmp.recycle();
		}
		
		byte[] result = output.toByteArray();
		try {
			output.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return result;
	}
	
	@Override
	public void forbid(IWebview pWebViewImpl) {
		if(api == null){
			api = WXAPIFactory.createWXAPI(pWebViewImpl.getActivity().getApplicationContext(),APPID,true);
		}
		api.unregisterApp();
	}

	public static final String AUTHORIZE_TEMPLATE = "{authenticated:%s,accessToken:'%s'}";
	@Override
	public void authorize(IWebview pWebViewImpl, String pCallbackId,String options) {

        JSONObject jsonOptions=JSONUtil.createJSONObject(options);
        if(jsonOptions != null){
            APPID = jsonOptions.optString(KEY_APPID, APPID);
            Logger.e(TAG, "authorize: appId"+APPID );
        }
		if(hasGeneralError(pWebViewImpl, pCallbackId)){
			return;
		}
		boolean ret = register(pWebViewImpl);
		if(ret){
			String msg = String.format(AUTHORIZE_TEMPLATE, ret,"");
			JSUtil.execCallback(pWebViewImpl, pCallbackId, msg, JSUtil.OK, true, false);
		}else{
			String msg = DOMException.toJSON(DOMException.CODE_BUSINESS_INTERNAL_ERROR, DOMException.toString(BaseResp.ErrCode.ERR_AUTH_DENIED, "Share微信分享", "授权失败", mLink));
			JSUtil.execCallback(pWebViewImpl, pCallbackId, msg, JSUtil.ERROR, true, false);
		}
	}
	private boolean register(IWebview pWebViewImpl) {
		if(api == null){
			api = WXAPIFactory.createWXAPI(pWebViewImpl.getActivity().getApplicationContext(), APPID,true);
		}
        boolean ret=false;
		// 将该app注册到微信
        if(!PdrUtil.isEmpty(APPID)){
            ret = api.registerApp(APPID);
        }
		return ret;
	}

	@Override
	public String getJsonObject(IWebview pWebViewImpl) {
		String _json = null;
		try {
			JSONObject _weiXinObj = new JSONObject();
			_weiXinObj.put(StringConst.JSON_SHARE_ID, WEIXIN_ID);
			_weiXinObj.put(StringConst.JSON_SHARE_DESCRIPTION, WEIXIN_DES);
			_weiXinObj.put(StringConst.JSON_SHARE_AUTHENTICATED,register(pWebViewImpl));
			_weiXinObj.put(StringConst.JSON_SHARE_ACCESSTOKEN, "");
			_weiXinObj.put(StringConst.JSON_SHARE_NATIVECLIENT, api.isWXAppInstalled());
			_json = _weiXinObj.toString();
		} catch (JSONException e) {
			e.printStackTrace();
		}
		return _json;
	}
	
	private boolean hasWXEntryActivity(Context context){
		String clsName = context.getPackageName() + ".wxapi.WXEntryActivity";
		try {
			Class.forName(clsName);
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	@Override
	public void dispose() {
		if(null != api){
			api.unregisterApp();
			api.detach();
		}
		api = null;
	}
}