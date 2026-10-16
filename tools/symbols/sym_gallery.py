WCS = "com.oplus.gallery.photoeditor.editingvvm.watermarkCamera.WatermarkCameraSection"
WPS = "com.oplus.gallery.photoeditor.editingvvm.watermarkpersonalized.WatermarkPersonalizedEditSection"
WMS = "com.oplus.gallery.framework.abilities.watermark.masterstyle.WatermarkMasterStyle"
WEP = "com.oplus.gallery.photoeditor.editingvvm.watermarkmaster.data.WatermarkEffectPhase"
WSDP = "com.oplus.gallery.framework.abilities.watermark.util.WatermarkStyleDataProcessor"
MVM = "com.oplus.gallery.photoeditor.editingvvm.watermarkmaster.WatermarkMasterVM"
BES = "com.oplus.gallery.photoeditor.editingvvm.BaseEditingSection"
def g(**kw):
    for k, v in kw.items(): G[k.replace("__", ".")] = v
g(
 Config__getBoolean=M(params=[S, "boolean", "boolean"], ret="boolean", strings=["getBoolean configAbility is null, configId:"]),
 CameraInfo__doubleSelect=M(params=[], ret="boolean", strings=["ai.master.support.double.select", "getCameraIsSupportDoubleSelect metadata failed"]),
 AssetStyleSource=K(strings=["AssetStyleFileSource(assetName='"]),
 FileStyleSource=K(strings=["FileStyleSource(filePath='"]),
 StyleSource=K(interfaceOf="@AssetStyleSource"),
 AssetStyleSource__name=F(**{"in": "@AssetStyleSource"}, type=S),
 StyleLoader__resolve=M(**{"in": "com.oplus.gallery.framework.abilities.watermarkmaster.b"}, params=["@StyleSource"], ret="@StyleSource"),
 Section__refreshStyles=M(**{"in": WCS}, params=[], ret="void", fields=["Lcom/oplus/gallery/photoeditor/R$string;->picture_edtior_text_watermark_ricoh_GR_title:I"]),
 Section__backButtonSetup=M(**{"in": WCS}, fields=["Lcom/oplus/gallery/photoeditor/R$id;->watermark_camera_back_btn:I"]),
 Section__segmentSetup=M(**{"in": WCS}, strings=["null cannot be cast to non-null type com.coui.appcompat.segmentbutton.COUISegmentButtonLayout"]),
 Section__onCreate=M(**{"in": WCS}, name="onCreate"),
 Section__cameraStyles=M(**{"in": WCS}, params=[], ret="boolean", calledBy=["@Section.backButtonSetup", "@Section.segmentSetup", "@Section.onCreate"]),
 Section__style=F(**{"in": WCS}, name="z0", type=S),
 Section__grChip=F(**{"in": WCS}, name="y", type="com.coui.appcompat.chip.COUIChip"),
 Section__otherChip=F(**{"in": WCS}, name="x", type="com.coui.appcompat.chip.COUIChip"),
 Section__root=F(**{"in": WCS}, name="q", type="androidx.constraintlayout.widget.ConstraintLayout"),
 Editing__context=F(**{"in": BES}, name="g", type="android.content.Context"),
 Personalized__setup=M(**{"in": WPS}, strings=["feature_is_support_hassel_device", "feature_is_support_video_watermark_extra_large_text_size"]),
 Personalized__style=F(**{"in": WPS}, name="g1", type=S),
 Personalized__labels=F(**{"in": WPS}, name="Y0", type="java.util.List"),
 Extend__parse=M(params=["java.util.Map", None, "int", "android.util.Size"], ret="kotlin.Pair", strings=["gr.effect.info"]),
 Brand__isRealme=M(params=[None, "android.content.Context"], ret="boolean", static=True, fields=["Lcom/oplus/gallery/foundation/util/R$string;->common_realme_brand_exp:I"]),
 # Watermark data of a photo and the photo-editor check that offers the hasselblad / LUMO / GR tabs.
 WatermarkInfo=K(strings=["WatermarkInfo("]),
 WatermarkContent=K(strings=["WatermarkContent(make="]),
 RealmeWatermarkInfo=K(strings=["RealmeWatermarkInfo(filter="]),
 MasterVM__photoSupports=M(**{"in": MVM}, static=True, params=[MVM, "int"], ret="boolean"),
 # Batch watermark: the editor's style application when a photo opens, and the style loader.
 MasterVM__onImagePack=M(**{"in": MVM}, strings=["[addWatermarkForStyleSync] styleId:"]),
 MasterVM__applyStyle=M(**{"in": MVM}, static=True, params=[MVM, S, WMS, None, WEP, "int"], ret="void"),
 StyleData__source=M(**{"in": WSDP}, static=True, params=[S], invokes=["Lcom/oplus/gallery/framework/abilities/watermark/masterstyle/WatermarkMasterStyle$Companion;->getStyleFileNameById(Ljava/lang/String;)Ljava/lang/String;"]),
)
