# OpenCV Image Processing (`cv.*`)

Built-in OpenCV bindings available in `run_js`. All operations run natively on-device — no network round-trip. Mat objects are opaque handles; they are automatically released when `run_js` finishes, but you can call `mat.release()` to free memory sooner.

Most `cv.*` functions are **async** and return Promises — use `await`. Only `mat.release()`, `mat.info`, `cv.zeros()`, and `cv.ones()` are sync (constant-time, no image data processed).

## Image I/O

```javascript
const img = await cv.imread("captures/photo.jpg");       // load from file
const gray = await cv.imread("captures/photo.jpg", cv.IMREAD_GRAYSCALE);
await cv.imwrite(img, "output/result.jpg");               // save to file

// From/to binary data
const bytes = await readBinaryFile("captures/photo.jpg");
const img2 = await cv.imdecode(bytes);                    // Uint8Array → Mat
const jpegBytes = await cv.imencode(img2, ".jpg");         // Mat → Uint8Array

// Base64
const b64 = await img.toBase64(".jpg");                   // Mat → base64 string
const img3 = await cv.fromBase64(b64);                    // base64 → Mat
```

## Mat Properties and Lifecycle

```javascript
const img = await cv.imread("photo.jpg");
img.info;       // {rows, cols, channels, type, depth, elemSize}
img.rows;       // height
img.cols;       // width
img.channels;   // 1 (gray), 3 (BGR), 4 (BGRA)

const copy = await img.clone();
img.release();  // free memory early (sync)

// Create blank Mats (sync — no image data)
const black = cv.zeros(480, 640, cv.CV_8UC3);
const white = cv.ones(480, 640, cv.CV_8UC1);

// Raw pixel data
const raw = await img.toBytes();                          // Uint8Array of all pixels
const mat = await cv.fromBytes(raw, 480, 640, cv.CV_8UC3);
```

## Color Conversion

```javascript
const gray = await cv.cvtColor(img, cv.COLOR_BGR2GRAY);
const hsv  = await cv.cvtColor(img, cv.COLOR_BGR2HSV);
const rgb  = await cv.cvtColor(img, cv.COLOR_BGR2RGB);
```

Constants: `COLOR_BGR2GRAY`, `COLOR_BGR2RGB`, `COLOR_BGR2HSV`, `COLOR_HSV2BGR`, `COLOR_BGR2HLS`, `COLOR_BGR2Lab`, `COLOR_BGR2YCrCb`, `COLOR_GRAY2BGR`, `COLOR_RGBA2BGR`, `COLOR_BGR2RGBA`.

## Geometric Transforms

```javascript
const small = await cv.resize(img, 320, 240);             // resize to 320×240
const small2 = await cv.resize(img, 320, 240, cv.INTER_AREA);  // with interpolation
const cropped = await cv.crop(img, 100, 50, 200, 150);    // x, y, width, height
const rotated = await cv.rotate(img, cv.ROTATE_90_CLOCKWISE);
const flipped = await cv.flip(img, 1);                     // 0=vertical, 1=horizontal, -1=both

// Affine rotation
const M = await cv.getRotationMatrix2D(320, 240, 45, 1.0); // center, angle, scale
const dst = await cv.warpAffine(img, M);

// Perspective transform
const src = [[0,0], [640,0], [640,480], [0,480]];
const dst_pts = [[50,0], [590,0], [640,480], [0,480]];
const P = await cv.getPerspectiveTransform(src, dst_pts);
const warped = await cv.warpPerspective(img, P, 640, 480);
```

Interpolation constants: `INTER_NEAREST`, `INTER_LINEAR`, `INTER_CUBIC`, `INTER_AREA`, `INTER_LANCZOS4`.

Rotation constants: `ROTATE_90_CLOCKWISE`, `ROTATE_180`, `ROTATE_90_COUNTERCLOCKWISE`.

## Filtering

```javascript
const blurred = await cv.blur(img, 5);                     // box blur 5×5
const gauss   = await cv.GaussianBlur(img, 5, 5, 1.5);    // Gaussian blur
const med     = await cv.medianBlur(img, 5);                // median blur (removes salt-and-pepper noise)
const bil     = await cv.bilateralFilter(img, 9, 75, 75);  // edge-preserving blur
```

## Edge Detection

```javascript
const edges   = await cv.Canny(gray, 50, 150);             // Canny edge detector
const lap     = await cv.Laplacian(gray, cv.CV_16SC1);     // Laplacian
const sobelX  = await cv.Sobel(gray, cv.CV_16SC1, 1, 0);   // Sobel X gradient
const sobelY  = await cv.Sobel(gray, cv.CV_16SC1, 0, 1);   // Sobel Y gradient
```

## Thresholding

```javascript
const r = await cv.threshold(gray, 127, 255, cv.THRESH_BINARY);
// r = {mat: Mat, threshold: number}

// Otsu's automatic thresholding
const r2 = await cv.threshold(gray, 0, 255, cv.THRESH_BINARY | cv.THRESH_OTSU);

const adaptive = await cv.adaptiveThreshold(gray, 255,
    cv.ADAPTIVE_THRESH_GAUSSIAN_C, cv.THRESH_BINARY, 11, 2);
```

Constants: `THRESH_BINARY`, `THRESH_BINARY_INV`, `THRESH_TRUNC`, `THRESH_TOZERO`, `THRESH_TOZERO_INV`, `THRESH_OTSU`, `THRESH_TRIANGLE`, `ADAPTIVE_THRESH_MEAN_C`, `ADAPTIVE_THRESH_GAUSSIAN_C`.

## Morphology

```javascript
const eroded  = await cv.erode(mask, 3, 3, 1);             // kernel w, h, iterations
const dilated = await cv.dilate(mask, 3, 3, 1);
const opened  = await cv.morphologyEx(mask, cv.MORPH_OPEN, 5);
const closed  = await cv.morphologyEx(mask, cv.MORPH_CLOSE, 5);
```

Operation constants: `MORPH_ERODE`, `MORPH_DILATE`, `MORPH_OPEN`, `MORPH_CLOSE`, `MORPH_GRADIENT`, `MORPH_TOPHAT`, `MORPH_BLACKHAT`.

Shape constants: `MORPH_RECT`, `MORPH_CROSS`, `MORPH_ELLIPSE`.

## Contours

```javascript
const contours = await cv.findContours(edges, cv.RETR_EXTERNAL, cv.CHAIN_APPROX_SIMPLE);
// contours = [{points: [[x,y], ...], area, arcLength, boundingRect: {x,y,w,h}}, ...]

const large = contours.filter(c => c.area > 500);
await cv.drawContours(img, large, -1, 0, 255, 0, 2);  // idx, R, G, B, thickness
```

Mode constants: `RETR_EXTERNAL`, `RETR_LIST`, `RETR_CCOMP`, `RETR_TREE`.

Method constants: `CHAIN_APPROX_NONE`, `CHAIN_APPROX_SIMPLE`.

## Drawing (mutates in-place, returns same Mat)

```javascript
await cv.rectangle(img, 10, 20, 100, 50, 255, 0, 0, 2);      // x, y, w, h, R, G, B, thickness
await cv.circle(img, 320, 240, 50, 0, 255, 0, 2);              // cx, cy, radius, R, G, B, thickness
await cv.line(img, 0, 0, 640, 480, 0, 0, 255, 2);              // x1, y1, x2, y2, R, G, B, thickness
await cv.putText(img, "Hello", 50, 100, cv.FONT_HERSHEY_SIMPLEX, 1.0, 255, 255, 255, 2);
```

Font constants: `FONT_HERSHEY_SIMPLEX`, `FONT_HERSHEY_PLAIN`, `FONT_HERSHEY_DUPLEX`, `FONT_HERSHEY_COMPLEX`, `FONT_HERSHEY_TRIPLEX`, `FONT_ITALIC`.

## Feature Detection

```javascript
// Corner detection
const corners = await cv.goodFeaturesToTrack(gray, 100, 0.01, 10);
// corners = [[x, y], ...]

// Template matching
const result = await cv.matchTemplate(img, template, cv.TM_CCOEFF_NORMED);
// result = {mat, minVal, maxVal, minLoc: [x,y], maxLoc: [x,y]}

// ORB feature detection
const orb = await cv.detectORB(gray, 500);
// orb = {keypoints: [{x, y, size, angle, response}, ...], descriptors: Mat|null}
```

Template matching constants: `TM_SQDIFF`, `TM_SQDIFF_NORMED`, `TM_CCORR`, `TM_CCORR_NORMED`, `TM_CCOEFF`, `TM_CCOEFF_NORMED`.

## Histograms

```javascript
const hist = await cv.calcHist(gray, 0, 256);   // channel, bins → [values...]
const eq = await cv.equalizeHist(gray);          // histogram equalization
```

## Arithmetic and Pixel Operations

```javascript
const diff = await cv.absdiff(img1, img2);                // absolute difference
const blend = await cv.addWeighted(img1, 0.7, img2, 0.3); // alpha blending
const result = await cv.bitwiseAnd(img, mask);
const result2 = await cv.bitwiseOr(img, mask);
const inv = await cv.bitwiseNot(img);
const converted = await cv.convertTo(img, cv.CV_32FC1, 1/255.0, 0);

// Color range mask (e.g., detect blue objects in HSV)
const hsv = await cv.cvtColor(img, cv.COLOR_BGR2HSV);
const mask = await cv.inRange(hsv, [100, 50, 50], [130, 255, 255]);
```

Type constants: `CV_8UC1`, `CV_8UC3`, `CV_8UC4`, `CV_16SC1`, `CV_32FC1`, `CV_64FC1`.

## Denoising

```javascript
const clean = await cv.fastNlMeansDenoising(gray, 3, 7, 21);
const cleanColor = await cv.fastNlMeansDenoisingColored(img, 3, 3, 7, 21);
```

## Complete Pipeline Example

```javascript
// Capture → preprocess → detect edges → find contours → annotate → save
const img = await cv.imread("captures/photo.jpg");
const small = await cv.resize(img, 640, 480);
const gray = await cv.cvtColor(small, cv.COLOR_BGR2GRAY);
const blurred = await cv.GaussianBlur(gray, 5, 5);
const edges = await cv.Canny(blurred, 50, 150);
const contours = await cv.findContours(edges, cv.RETR_EXTERNAL, cv.CHAIN_APPROX_SIMPLE);
const large = contours.filter(c => c.area > 500);
await cv.drawContours(small, large, -1, 0, 255, 0, 2);
for (const c of large) {
    const r = c.boundingRect;
    await cv.rectangle(small, r.x, r.y, r.w, r.h, 255, 0, 0, 1);
}
await cv.imwrite(small, "processed/annotated.jpg");
[img, small, gray, blurred, edges].forEach(m => m.release());
JSON.stringify({contours: large.length});
```
