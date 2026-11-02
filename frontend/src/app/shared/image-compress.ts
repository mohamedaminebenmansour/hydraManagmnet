/**
 * Compress an image file to JPEG, max long side 1600 px, quality 0.75 (~300 KB).
 * Uses createImageBitmap with imageOrientation: 'from-image' so phone photos are
 * not rotated (EXIF orientation applied). Falls back to an <img> element if
 * createImageBitmap is missing. If the file is already <= 300 KB and <= maxSide,
 * returns it unchanged.
 */
export async function compressImage(file: File, maxSide = 1600, quality = 0.75): Promise<File> {
  // load the image with EXIF orientation
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' } as any);
  } catch {
    bitmap = await loadViaImg(file);
  }
  const w = bitmap.width, h = bitmap.height;
  const longSide = Math.max(w, h);
  // already small enough + within maxSide → return unchanged
  if (file.size <= 300 * 1024 && longSide <= maxSide) {
    bitmap.close?.();
    return file;
  }
  // scale so the long side <= maxSide
  const scale = longSide > maxSide ? maxSide / longSide : 1;
  const cw = Math.round(w * scale), ch = Math.round(h * scale);
  const canvas = document.createElement('canvas');
  canvas.width = cw; canvas.height = ch;
  const ctx = canvas.getContext('2d');
  if (!ctx) { bitmap.close?.(); return file; }
  ctx.drawImage(bitmap, 0, 0, cw, ch);
  bitmap.close?.();
  const blob = await new Promise<Blob>((resolve) => {
    canvas.toBlob(b => resolve(b ?? new Blob([], { type: 'image/jpeg' })), 'image/jpeg', quality);
  });
  const name = file.name.replace(/\.[^.]+$/, '') + '.jpg';
  return new File([blob], name, { type: 'image/jpeg' });
}

/** Fallback: load via an <img> element (no EXIF orientation fix). */
function loadViaImg(file: File): Promise<ImageBitmap> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const url = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(url);
      if (typeof createImageBitmap === 'function') {
        createImageBitmap(img).then(resolve, reject);
      } else {
        const canvas = document.createElement('canvas');
        canvas.width = img.naturalWidth; canvas.height = img.naturalHeight;
        canvas.getContext('2d')!.drawImage(img, 0, 0);
        createImageBitmap(canvas).then(resolve, reject);
      }
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('Image illisible')); };
    img.src = url;
  });
}
