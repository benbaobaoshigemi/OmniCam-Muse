STAGE_SWITCH = "com.oplus.camera.protocal.event.message.modeProtocol.EventMessageModeSwitcherStage"
add(
 # capture stage: the request builder field (C) is the one whose type takes the picture callback
 StageRequest__pictureCallback=M(params=["com.oplus.camera.aps.service.ApsService$CameraPictureCallbackImpl"], ret="void", abstract=True),
 StageRequestType=K(ownerOf="@StageRequest.pictureCallback"),
 Stage__request=F(**{"in": STAGE}, type="@StageRequestType"),
 # mode switch stage (non-obfuscated class; field names checked with their types)
 ModeSwitchStage__stage=F(**{"in": STAGE_SWITCH}, name="b", type="int"),
 ModeSwitchStage__toMode=F(**{"in": STAGE_SWITCH}, name="e", type=S),
)

MSB = "com.oplus.camera.control.MainShutterButton"
add(
 Zoom__labelBitmap=F(**{"in": ZSB}, name="I6", type="android.graphics.Bitmap"),
 Zoom__fadeLabels=M(**{"in": ZSB}, name="b", params=[], ret="void"),
 ZoomBase__fadeLabels=M(**{"in": "@ZoomBase"}, overrideOf="@Zoom.fadeLabels"),
 ZoomUi__bar=F(**{"in": "@ZoomUi"}, readBy=["@ZoomUi.setUnit"]),
 Card__texts=F(**{"in": "@Card"}, type="com.oplus.camera.common.view.StrokeTextView", multi=True),
 SoundPlayer__refreshedInts=F(**{"in": "@SoundPlayer"}, type="int", writtenBy=["@SoundPlayer.refresh"], multi=True),
)
for n in ["b3", "c3", "d3", "e3", "g3", "h3", "l3"]:
    add(**{"Shutter__" + n: F(**{"in": MSB}, name=n, type="android.graphics.RectF")})
add(Shutter__t4=F(**{"in": MSB}, name="t4", type="android.graphics.SweepGradient"),
    Shutter__H4=F(**{"in": MSB}, name="H4", type="android.graphics.LinearGradient"),
    Shutter__I4=F(**{"in": MSB}, name="I4", type="android.graphics.LinearGradient"),
    Shutter__s3=F(**{"in": MSB}, name="s3", type="android.graphics.Paint"),
    Shutter__j1=F(**{"in": MSB}, name="j1", type="int"),
    Shutter__k1=F(**{"in": MSB}, name="k1", type="int"))

# BitmapUtil.cropCenterBitmapByScale: returns null when the scale is below 1 (SeamlessBlurGuard)
add(BitmapUtil__cropCenterByScale=M(params=["android.graphics.Bitmap", "float", "float"], ret="android.graphics.Bitmap", static=True,
                                    strings=["cropCenterBitmapByScale, error, sw: "]))

# master effect bar (影调 / 饱和度 / … / 暗角): MasterGrain adds 颗粒 before 暗角, with the professional
# white balance / colour tone pair of scale bars for its strength and size
EM = "@EffectMenuBase"
SB = "com.oplus.camera.coui.seekbar.HSScaleBar"
add(
 EffectMenu=K(strings=["MasterEffectMenuV2"]),
 EffectMenuBase=K(superOf="@EffectMenu"),
 EffectMenuBase__items=F(**{"in": EM}, type="java.util.ArrayList"),
 EffectMenuBase__seekBar=F(**{"in": EM}, type=SB),
 EffectMenuBase__restore=M(**{"in": EM}, params=["int", "boolean"], strings=["BaseMasterEffectMenu"]),
 EffectMenuBase__report=M(**{"in": EM}, strings=["funcValue_select"]),
 EffectMenuBase__hideSeekBar=M(**{"in": EM}, params=["boolean"], ret="void", invokes=["@EffectMenuBase.report"]),
 EffectMenuBase__refreshItem=M(**{"in": EM}, params=["int"], ret="void", calledBy=["@EffectMenuBase.restore"]),
 EffectMenuBase__refreshReset=M(**{"in": EM}, params=["boolean"], ret="void", calledBy=["@EffectMenuBase.restore"]),
 # the menu's host (FilterSource): showing a scale bar hides the filter name card and dots, hiding it shows them
 EffectHost=K(interfaceOf="@FilterSource"),
 EffectMenuBase__host=F(**{"in": EM}, type="@EffectHost"),
 EffectMenu__showSeekBar=M(**{"in": "@EffectMenu"}, strings=["showSeekBar, menu is not opened, return"]),
 EffectMenu__hideSeekBar=M(**{"in": "@EffectMenu"}, overrideOf="@EffectMenuBase.hideSeekBar"),
 FilterSource__hideFilterUi=M(**{"in": "@FilterSource"}, params=[], ret="void", strings=["com.oplus.feature.effect.style.support"], calledBy=["@EffectMenu.showSeekBar"]),
 FilterSource__showFilterUi=M(**{"in": "@FilterSource"}, params=[], ret="void", strings=["com.oplus.feature.effect.style.support"], calledBy=["@EffectMenu.hideSeekBar"]),
 # the camera's vibrator (VibrateManager): play(1, 0) strong tick, play(0, 0) light tick, as the master bar's scale ticks
 Vibrate=K(strings=["blockVibration, invalid block option: "]),
 Vibrate__instance=F(type="@Vibrate", static=True),
 Vibrate__play=M(**{"in": "@Vibrate"}, params=["int", "int"], ret="void"),
 EffectMenu__refreshReset=M(**{"in": "@EffectMenu"}, overrideOf="@EffectMenuBase.refreshReset"),
 # "any item differs from its default", which enables the reset button
 EffectMenu__modified=M(**{"in": "@EffectMenu"}, params=[], ret="boolean", calledBy=["@EffectMenu.refreshReset"]),
 # CaptureParamPresenter's value callback: stores the value per preset, then applies the stock axes
 EffectMenu__dispatch=M(params=["int", DK], ret="void", calledBy=["@EffectMenuBase.restore"]),
 EffectItem__key=F(type=DK, static=False, readBy=["@EffectMenuBase.restore"]),
 EffectItem=K(ownerOf="@EffectItem.key"),
 EffectItem__value=F(**{"in": "@EffectItem"}, type="int", writtenBy=["@EffectMenuBase.restore"]),
 EffectItem__label=F(**{"in": "@EffectItem"}, name="a", type="int"),
 EffectItem__icon=F(**{"in": "@EffectItem"}, name="b", type="int"),
 # the item at an index has a -100..100 scale (冷暖, 青品, 饱和度, 影调); others 0..100
 EffectMenu__bipolar=M(**{"in": "@EffectMenu"}, params=["int"], ret="boolean"),
 EffectContainer=K(strings=["MasterEffectContainer"], super="android.widget.LinearLayout"),
 # collapsed, the row is shifted by half of (bar width - row width) to centre it; the bar width is the scroll view's
 # less twice sideMargin. updateBounds recomputes the reset capsule and the item backdrop from the row's position
 EffectContainer__sideMargin=F(**{"in": "@EffectContainer"}, name="q", type="int"),
 EffectContainer__updateBounds=M(**{"in": "@EffectContainer"}, params=[], ret="void", strings=["updateBlurBgBounds, calLeftAndRight is null, return"]),
 EffectItemView=K(strings=["MasterEffectContainer", "expand"]),
 EffectItemView__onClick=M(**{"in": "@EffectItemView"}, name="onClick"),
 EffectItemView__item=F(**{"in": "@EffectItemView"}, type="@EffectItem"),
 EffectItemView__bind=M(**{"in": "@EffectItemView"}, params=["boolean", "boolean"], ret="void"),
 EffectItemView__name=F(**{"in": "@EffectItemView"}, type="android.widget.TextView"),
 EffectItemView__icon=F(**{"in": "@EffectItemView"}, type="android.widget.ImageView"),
 EffectValueView=K(super="@EffectItemView", fieldTypes=["android.widget.TextView"]),
 EffectValueView__bind=M(**{"in": "@EffectValueView"}, overrideOf="@EffectItemView.bind"),
 EffectValueView__value=F(**{"in": "@EffectValueView"}, type="android.widget.TextView"),
 ScaleBar__setListener=M(**{"in": SB}, name="setScaleBarValueChangeListener"),
 ScaleBarListener=K(paramOf=["@ScaleBar.setListener", 0]),
 # position the bar on an index: select (index + notify flag), then scroll the scale to it
 ScaleBar__select=M(**{"in": SB}, name="L", params=["int", "boolean"], ret="void"),
 ScaleBar__scrollTo=M(**{"in": SB}, name="G", params=["int"], ret="void"),
 FilterNames__presenter=F(**{"in": "@FilterNames"}, type="@CaptureParams"),
 # current filter: the presenter's filter index into the mode's filter types (FilterNames.types)
 CaptureParams__filterIndex=M(**{"in": "@CaptureParams"}, params=[], ret="int", strings=["com.oplus.camera.feature.filter"]),
 # preference access: the store type names the DataKey group ("mmkv" / "sp")
 Prefs__storage=M(params=[], ret=S, static=True, strings=["mmkv", "sp"]),
 Prefs__get=M(params=[DK, S, "java.lang.Class", "java.lang.Object"], ret="java.lang.Object", static=True),
 Prefs__put=M(params=[DK, S, "java.lang.Class", "java.lang.Object"], ret="void", static=True,
              invokes=[f"L{DM.replace('.', '/')};->g(L{DK.replace('.', '/')};Ljava/lang/Object;)V"]),
)

# master LUT slots (MasterLutSlots): update() recombines the preview table from the current parameters
L3D = "com.oplus.camera.common.utils.Lut3dDataManager"
add(
 Lut3d__update=M(**{"in": L3D}, params=[], ret="void", strings=["updateBasicToneData, palette branch skip process, capModeName: "]),
)
