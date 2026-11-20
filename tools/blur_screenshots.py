"""Blur the camera view in DriveAware landscape screenshots (privacy), keeping the UI readable.
Usage: python3 tools/blur_screenshots.py <input_folder> <output_folder>   (needs: pip install opencv-python numpy)
Keeps the debug overlay text, status chips and alert banners sharp; portrait screenshots are copied unchanged."""
import cv2, glob, os, sys
import numpy as np
src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
for f in sorted(glob.glob(os.path.join(src, "*.png"))):
    im = cv2.imread(f); h, w = im.shape[:2]; name = os.path.basename(f)
    if h > w:
        cv2.imwrite(os.path.join(dst, name), im); print(name, "portrait, unchanged"); continue
    cam_x0, cam_x1 = int(w * 0.045), int(w * 0.655)        # camera preview columns
    prev = im[:, cam_x0:cam_x1].copy()
    small = cv2.resize(prev, (prev.shape[1] // 40, prev.shape[0] // 40), interpolation=cv2.INTER_AREA)
    blur = cv2.GaussianBlur(cv2.resize(small, (prev.shape[1], prev.shape[0]), interpolation=cv2.INTER_NEAREST), (0, 0), 25)
    lum = cv2.cvtColor(prev, cv2.COLOR_BGR2GRAY)
    keep = np.zeros(lum.shape, bool)
    ov_bottom, ov_right = int(h * 0.555), int((cam_x1 - cam_x0) * 0.82)
    # overlay text + chips: bright pixels in the top band
    top = np.zeros_like(keep); top[:ov_bottom, :ov_right] = True; top[:int(h * 0.13), :] = True
    keep |= top & (lum > 175)
    keep = cv2.dilate(keep.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool)
    # alert banners: rows where most pixels share one colour
    med = np.median(prev, axis=1, keepdims=True)
    uniform = (np.abs(prev.astype(int) - med).sum(axis=2) < 40).mean(axis=1) > 0.55
    # alert banner = the run of uniform rows that reaches the bottom of the preview (small gaps for text)
    top_row, gap, r = None, 0, h - 1
    while r > h * 0.4:
        if uniform[r]: top_row, gap = r, 0
        else:
            gap += 1
            if gap > 30: break
        r -= 1
    if top_row is not None and (h - top_row) > 0.15 * h:
        keep[top_row:, :] = True
    keep[: int(h * 0.17), :] = True                # status chips and the top of the overlay (no face there)
    out = np.where(keep[..., None], prev, blur)
    im[:, cam_x0:cam_x1] = out
    cv2.imwrite(os.path.join(dst, name), im); print(name, "blurred")
