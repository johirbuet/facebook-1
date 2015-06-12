package com.tealeaf.plugin.plugins;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import com.tealeaf.logger;
import com.tealeaf.TeaLeaf;
import com.tealeaf.EventQueue;
import com.tealeaf.plugin.IPlugin;
import com.tealeaf.plugin.PluginManager;
import java.io.*;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import java.net.URI;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.util.Log;
import android.os.Bundle;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Date;
import java.io.StringWriter;
import java.io.PrintWriter;

import android.net.Uri;
import android.view.Window;
import android.view.WindowManager;

import com.facebook.*;
import com.facebook.internal.*;

import com.facebook.FacebookDialogException;
import com.facebook.FacebookException;
import com.facebook.FacebookOperationCanceledException;
import com.facebook.FacebookAuthorizationException;
import com.facebook.FacebookRequestError;
import com.facebook.FacebookServiceException;
import com.facebook.login.*;
import com.facebook.share.model.GameRequestContent;
import com.facebook.share.widget.GameRequestDialog;

import java.util.Set;

public class FacebookPlugin implements IPlugin {
  Context _context;
  private Activity _activity;

  Integer activeRequest = null;

  String _facebookAppID = "";
  String _facebookDisplayName = "";

  private String appID = null;
  private String userID = null;


  public static final int INVALID_ERROR = -2;
  private CallbackManager callbackManager;
  private AccessTokenTracker accessTokenTracker;
  private GameRequestDialog requestDialog;


  void onJSONException (JSONException e) {
    logger.log("{facebook} JSONException:", e.getMessage());
  }

  // ---------------------------------------------------------------------------
  // JavaScript interface
  // ---------------------------------------------------------------------------

  public void facebookInit (String s_json) {
    logger.log("{facebook} init");

    JSONObject res;
    try {
      res = new JSONObject(s_json);
    } catch (JSONException e) {
      onJSONException(e);
      return;
    }

    logger.log("\t", res);

    appID = res.optString("appId", "");
  }

  public void getLoginStatus(String s_opts, Integer requestId) {
    logger.log("{facebook} getLoginStatus");
    sendResponse(getResponse(), null, requestId);
  }

  public void getAuthResponse(String s_opts, Integer requestId) {
    logger.log("{facebook} getAuthResponse");
    JSONObject authResponse = getResponse();
    JSONObject response = new JSONObject();
    try {
      response.put("authResponse", authResponse);
    } catch (JSONException e) {
      // What could possibly cause this to throw an error?
    }

    sendResponse(response, null, requestId);
  }

  public void login(String s_opts, Integer requestId) {
    activeRequest = requestId;

    logger.log("{facebook} login");
    String errorMessage;
    JSONObject opts;
    try {
      opts = new JSONObject(s_opts);
    } catch (JSONException e) {
      onJSONException(e);
      sendResponse(getErrorResponse("error parsing json opts"), requestId);
      return;
    }

    String scopes = opts.optString("scope", "");

    // Get the permissions
    String[] arrayPermissions = scopes.split(",");

    List<String> permissions = null;
    if (arrayPermissions.length > 0) {
      permissions = Arrays.asList(arrayPermissions);
    }

    // Check if already logged in
    if (isLoggedIn()) {

      // Reauthorize flow
      boolean publishPermissions = false;
      boolean readPermissions = false;

      // Figure out if this will be a read or publish reauthorize
      if (permissions == null) {
        // No permissions, read
        readPermissions = true;
      }

      // Loop through the permissions to see what is being requested
      for (String permission : arrayPermissions) {
        if (isPublishPermission(permission)) {
          publishPermissions = true;
        } else {
          readPermissions = true;
        }
        // Break if we have a mixed bag, as this is an error
        if (publishPermissions && readPermissions) {
          break;
        }
      }

      if (publishPermissions && readPermissions) {
        try {
          JSONObject err = new JSONObject();
          err.put("error", "Cannot ask for both read and publish permissions.");
          sendResponse(err, null, requestId);
        } catch (JSONException e) {
          onJSONException(e);
        }
        activeRequest = null;
        return;
      } else {

        // Check for write permissions, the default is read (empty)
        if (publishPermissions) {
          // Request new publish permissions
         LoginManager.getInstance().logInWithPublishPermissions(
                 _activity,
                 permissions
                );

        } else {
          // Request new read permissions
         LoginManager.getInstance().logInWithReadPermissions(
                 _activity,
                 permissions
                );
        }
      }
    } else {
      // TODO: is this separation needed in v4?
      LoginManager.getInstance().logInWithReadPermissions(
             _activity,
             permissions
            );
    }
  }

  public void api (String s_json, Integer _requestId) {

    final Integer requestId = _requestId;
    log("api");

    JSONObject opts;
    JSONObject _params;
    String _method;
    String path;

    try {
      opts = new JSONObject(s_json);
      _params = opts.getJSONObject("params");
      _method = opts.optString("method", "get");
      path = opts.getString("path");
    } catch (JSONException e) {
      onJSONException(e);
      sendResponse(getErrorResponse("error parsing JSON opts"), requestId);
      return;
    }

    Bundle params = null;
    if (_params.length() > 0) {
      try {
        params = BundleJSONConverter.convertToBundle(_params);
      } catch (JSONException e) {
        log("api - error converting JSONObject to Bundle");
      }
    }

    HttpMethod method;
    if (_method.equals("post")) {
      method = HttpMethod.POST;
    } else if (_method.equals("delete")) {
      method = HttpMethod.DELETE;
    } else {
      method = HttpMethod.GET;
    }


    if (path.charAt(0) == '/') {
      path = (new StringBuilder(path)).deleteCharAt(0).toString();
    }

    GraphRequest req = new GraphRequest(AccessToken.getCurrentAccessToken(), path, params, method, new GraphRequest.Callback() {
      @Override
      public void onCompleted(GraphResponse res) {
        if (res.getError() != null) {
          sendResponse(getFacebookRequestError(res.getError()), null, requestId);
        } else {
          sendResponse(res.getRawResponse(), null, requestId);
        }
      }
    });
    req.executeAndWait();
  }

  public void ui(String s_json, Integer requestId) {
    log("ui");
    final Integer _requestId = requestId;

    JSONObject json = null;
    Bundle params = null;

    // Parse JSON;
    try {
      json = new JSONObject(s_json);
    } catch (JSONException e) {
      onJSONException(e);
      log("ui failed to parse JSON blob");
      sendResponse(getErrorResponse("failed to parse JSON"), null, requestId);
      return;
    }

    if (json.length() > 0) {
      // get json bundle
      try {
        params = BundleJSONConverter.convertToBundle(json);
      } catch (JSONException e) {
        log("ui failed to convert JSON to bundle");
        onJSONException(e);
        sendResponse(
          getErrorResponse("error converting JSONObject to bundle"),
          null,
          _requestId
        );
        return;
      }
    }

    final String method = params.getString("method");
    if (method == null) {
      log("ui failed - method param is required");
      sendResponse(getErrorResponse("method param is required"), null, requestId);
      return;
    }

    params.remove("method");
    final Bundle dialogParams = params;


    // TODO: replace this with whatever it has been changed to
    /*
    // Setup callback context
    final OnCompleteListener dialogCallback = new OnCompleteListener() {
      @Override
      public void onComplete(Bundle values, FacebookException exception) {
        if (exception != null) {
          handleError(exception, _requestId);
        } else {
          try {
            JSONObject res = BundleJSONConverter.convertToJSON(values);
            sendResponse(res.toString(), null, _requestId);
          } catch (JSONException e) {
            sendResponse(
              getErrorResponse(exception.getMessage()), null, _requestId
            );
          }
        }
      }
    };
    */

    final Activity devkitActivity = _activity;
    if (method.equalsIgnoreCase("feed")) {
        /*
      Runnable runnable = new Runnable() {
        public void run() {
          WebDialog feedDialog = (new WebDialog.FeedDialogBuilder(
            devkitActivity,
            Session.getActiveSession(),
            dialogParams)
          ).setOnCompleteListener(dialogCallback).build();

          feedDialog.show();
        }
      };
      devkitActivity.runOnUiThread(runnable);
        */
    } else if (method.equalsIgnoreCase("apprequests")) {

        ArrayList<String> filtersArray = dialogParams.getStringArrayList("filters");
        String filtersString = "";
        GameRequestContent.Filters filters = null;
        if (filtersArray != null) {
            filtersString = filtersArray.get(0);

            if (filtersString.equalsIgnoreCase("app_non_users")) {
                filters = GameRequestContent.Filters.APP_NON_USERS;
            } else {
                filters = GameRequestContent.Filters.APP_USERS;
            }
        }

        String objectId = dialogParams.getString("objectId");
        GameRequestContent.ActionType actionType = null;
        String actionTypeString = dialogParams.getString("actionType");

        if (actionTypeString != null) {
            actionType = GameRequestContent.ActionType.valueOf(actionTypeString);
        }


        activeRequest = requestId;
        GameRequestContent.Builder builder = new GameRequestContent.Builder()
            .setMessage(dialogParams.getString("message"))
            .setTitle(dialogParams.getString("title"));

        if (filters != null) {
            builder.setFilters(filters);
        }

        if (actionType != null && objectId != null) {
            builder
                .setObjectId(objectId)
                .setActionType(actionType);
        }

        GameRequestContent content = builder.build();
        requestDialog.show(content);

    } else if (method.equalsIgnoreCase("share") || method.equalsIgnoreCase("share_open_graph")) {
        /*
      Boolean canPresentShareDialog = FacebookDialog.canPresentShareDialog(
        devkitActivity,
        FacebookDialog.ShareDialogFeature.SHARE_DIALOG
      );

      if (canPresentShareDialog) {
        Runnable runnable = new Runnable() {
          public void run() {
            // Publish the post using the Share Dialog
            FacebookDialog shareDialog = new FacebookDialog.ShareDialogBuilder(devkitActivity)
              .setName(dialogParams.getString("name"))
              .setCaption(dialogParams.getString("caption"))
              .setDescription(dialogParams.getString("description"))
              .setLink(dialogParams.getString("href"))
              .setPicture(dialogParams.getString("picture"))
              .build();
            shareDialog.present();
          }
        };

        devkitActivity.runOnUiThread(runnable);
      } else {
        // Fallback. For example, publish the post using the Feed Dialog
        Runnable runnable = new Runnable() {
          public void run() {
            WebDialog feedDialog = (new WebDialog.FeedDialogBuilder(
              devkitActivity,
              Session.getActiveSession(),
              dialogParams)
            ).setOnCompleteListener(dialogCallback).build();
            feedDialog.show();
          }
        };
        devkitActivity.runOnUiThread(runnable);
      }
      */
    } else {
      sendResponse(getErrorResponse("unsupported method"), requestId);
    }
  }

  public void logout(String json, Integer requestId) {
    LoginManager.getInstance().logOut();

    JSONObject res = new JSONObject();
    try {
      res.put("state", "closed");
    } catch (JSONException e) {
      onJSONException(e);
    }
    sendResponse(res, null, requestId);
  }

  // ---------------------------------------------------------------------------
  // Facebook Interface Utilities
  // ---------------------------------------------------------------------------


  private static final Set<String> publishPermissionsSet = new HashSet<String>() {
    {
      add("ads_management");
      add("create_event");
      add("rsvp_event");
    }
  };

  private boolean isPublishPermission(String permission) {
    return permission != null &&
      (permission.startsWith("publish")) ||
      (permission.startsWith("manage")) ||
      publishPermissionsSet.contains(permission);
  }

  /**
   * Check if user is logged in.
   *
   * @return boolean
   */

  public boolean isLoggedIn() {
    return AccessToken.getCurrentAccessToken() != null;
  }

  /**
   * Create a Facebook Response object that matches the one for the Javascript
   * SDK
   *
   * @return JSONObject - the response object
   */

  public JSONObject getResponse() {
    String response;


    // TODO: go find the new js api
    if (isLoggedIn()) {

      AccessToken currentToken = AccessToken.getCurrentAccessToken();

      Date today = new Date();
      long expirationTime = currentToken.getExpires().getTime();
      long expiresTimeInterval = (expirationTime - today.getTime()) / 1000L;
      long expiresIn = (expiresTimeInterval > 0) ? expiresTimeInterval : 0;
      // Make list of grantedScopes
      final Set<String> permissions = currentToken.getPermissions();
      StringBuilder sb = new StringBuilder();
      String comma = ",";
      for (String perm : permissions) {
        sb.append(perm).append(comma);
      }
      sb.setLength(sb.length() - comma.length());
      String grantedScopes = sb.toString();

      response = "{"
        + "\"status\": \"connected\","
        + "\"authResponse\": {"
        + "\"accessToken\": \"" + currentToken + "\","
        + "\"expiresIn\": \"" + expiresIn + "\","
        + "\"sig\": \"...\","
        + "\"grantedScopes\": \"" + grantedScopes + "\","
        + "\"userID\": \"" + userID + "\""
        + "}"
        + "}";
    } else {
      response = "{"
        + "\"status\": \"unknown\""
        + "}";
    }

    try {
      return new JSONObject(response);
    } catch (JSONException e) {

      e.printStackTrace();
    }
    return new JSONObject();
  }

  /**
   * Create JSON object for facebook err request error
   *
   * @return JSONObject
   */

  public JSONObject getFacebookRequestError(FacebookRequestError e) {
    JSONObject err = new JSONObject();
    JSONObject res = new JSONObject();

    try {
      err.put("code", e.getErrorCode());
      err.put("type", e.getErrorType());
      err.put("message", e.getErrorMessage());
      res.put("error", err);
    } catch (JSONException exception) {
      onJSONException(exception);
    }

    return res;
  }

  /**
   * Create JSON object to be returned to JS
   */

  public JSONObject getErrorResponse(Exception err, String msg, int code) {
    if (err instanceof FacebookServiceException) {
      return getFacebookRequestError(
        ((FacebookServiceException) err).getRequestError()
      );
    }

    if (err instanceof FacebookDialogException) {
      code = ((FacebookDialogException) err).getErrorCode();
    }

    if (msg == null) {
      msg = err.getMessage();
    }

    JSONObject jsonError = new JSONObject();
    JSONObject ret = new JSONObject();

    try {
      jsonError.put("code", code);
      jsonError.put("message", msg);
      ret.put("error", jsonError);
    } catch (JSONException e) {
      onJSONException(e);
      ret = new JSONObject();
    }

    return ret;
  }

  private JSONObject getErrorResponse(String msg) {
    JSONObject response = new JSONObject();
    try {
      response.put("error", msg);
    } catch (JSONException e) {
      log("JSON exception while constructing error response");
    }

    return response;
  }




  private void onAccessTokenChange(AccessToken oldToken, AccessToken token) {
    JSONObject payload = new JSONObject();

    if (token != null && oldToken == null) {
      // sendEvent("auth.login", payload);
      sendEvent("auth.statusChange", payload);
    } else if (token == null && oldToken != null) {
      sendEvent("logout", payload);
      sendEvent("auth.statusChange", payload);
    } else {
      sendEvent("auth.authResponseChanged", payload);
    }
  }

  private static void sendEvent(String event, Object payload) {
    String _payload = "";
    if (payload instanceof JSONObject) {
      _payload = payload.toString();
    } else if (payload instanceof String) {
      _payload = (String)payload;
    } else {
      log("sendEvent cannot coerce payload to string");
    }

    PluginManager.sendEvent(event, "FacebookPlugin", _payload);
  }

  private void sendResponse(Object res, String err, Integer requestId) {
    if (activeRequest == requestId) { activeRequest = null; }

    String response = "";

    if (res instanceof JSONObject) {
      response = res.toString();
    } else if (res instanceof String) {
      response = (String) res;
    }

    log("sendResponse", "requestId:", requestId);
    if (requestId != null) {
      PluginManager.sendResponse(response, err, requestId);
    }
  }

  private void sendResponse(Object res, Integer requestId) {
    sendResponse(res, null, requestId);
  }

  public static void log(Object... args) {
    Object[] newArgs = new Object[args.length + 1];
    newArgs[0] = "{facebook}";
    System.arraycopy(args, 0, newArgs, 1, args.length);
    logger.log(newArgs);
  }

  private void handleError(Exception e, Integer requestId) {
    String msg;
    int code = INVALID_ERROR;

    if (e instanceof FacebookOperationCanceledException) {
      // Send undefined
      sendResponse(null, null, requestId);
    } else {
      if (e instanceof FacebookDialogException) {
        msg = "Dialog exception: " + e.getMessage();
      } else {
        msg = e.getMessage();
      }

      JSONObject res = getErrorResponse(e, msg, code);
      sendResponse(res, null, requestId);
    }
  }

  // ---------------------------------------------------------------------------
  // Plugin Interface Methods
  // ---------------------------------------------------------------------------

  public void onCreateApplication(Context applicationContext) {
    _context = applicationContext;
  }

  public void onCreate(Activity activity, Bundle savedInstanceState) {
    _activity = activity;

    PackageManager manager = _activity.getPackageManager();
    try {
      Bundle meta = manager.getApplicationInfo(_activity.getPackageName(), PackageManager.GET_META_DATA).metaData;
      if (meta != null) {
        _facebookAppID = meta.get("FACEBOOK_APP_ID").toString();
        _facebookDisplayName = meta.get("FACEBOOK_DISPLAY_NAME").toString();
      }

      FacebookSdk.sdkInitialize(_activity.getApplicationContext());

      callbackManager = CallbackManager.Factory.create();

      accessTokenTracker = new AccessTokenTracker() {
           @Override
           protected void onCurrentAccessTokenChanged(
                   AccessToken oldAccessToken,
                   AccessToken currentAccessToken) {
               // App code
               onAccessTokenChange(oldAccessToken, currentAccessToken);
           }
      };

      LoginManager.getInstance().registerCallback(callbackManager,
            new FacebookCallback<LoginResult>() {
                @Override
                public void onSuccess(LoginResult loginResult) {
                  log("facebook login response - success");
                  JSONObject response = getResponse();
                  sendEvent("auth.login", response);
                   // respond to login request
                  sendResponse(response, null, activeRequest);
                }

                @Override
                public void onCancel() {
                  log("facebook login response - cancel");
                    // TODO: is this really the best way to say user cancelled?
                  handleError(new FacebookOperationCanceledException(), activeRequest);
                }

                @Override
                public void onError(FacebookException exception) {
                  log("facebook login response - error");
                  handleError(exception, activeRequest);
                }
      });

      requestDialog = new GameRequestDialog(_activity);
      requestDialog.registerCallback(callbackManager, new FacebookCallback<GameRequestDialog.Result>() {
          public void onSuccess(GameRequestDialog.Result result) {
              log("game request result - success");
              sendResponse("", null, activeRequest);
          }
          public void onCancel() {
              log("game request result - success");
              sendResponse("", "cancelled", activeRequest);
          }
          public void onError(FacebookException error) {
              log("game request result - success");
              sendResponse("", "error", activeRequest);
          }
      });

      JSONObject ready = new JSONObject();
      try { ready.put("status", "OK"); } catch (JSONException e) {}
      PluginManager.sendEvent("FacebookPluginReady", "FacebookPlugin", ready);

    } catch (Exception e) {
      log("{facebook} Exception on start:", e.getMessage());
    }
  }

  public void onResume() {
  }

  public void onStart() {
  }

  public void onPause() {

  }

  public void onStop() {

  }

  public void onDestroy() {
    accessTokenTracker.stopTracking();
  }

  public void onNewIntent(Intent intent) {

  }

  public void setInstallReferrer(String referrer) {

  }

  @Override
  public void onActivityResult(Integer requestCode, Integer resultCode, Intent data) {
    callbackManager.onActivityResult(requestCode, resultCode, data);
  }
}
