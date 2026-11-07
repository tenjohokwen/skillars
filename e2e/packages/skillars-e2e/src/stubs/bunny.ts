import type { Page, Route } from '@playwright/test'

// The browser uploads video bytes straight to Bunny Stream's TUS endpoint, which the app hard-codes
// (BunnyVideoProviderAdapter.TUS_ENDPOINT), so the stack cannot point it at WireMock. This answers
// it in the page instead: enough of the TUS protocol for tus-js-client to finish an upload.

const TUS_ENDPOINT = 'https://video.bunnycdn.com/tusupload'

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, PATCH, HEAD, OPTIONS',
  'Access-Control-Allow-Headers': '*',
  'Access-Control-Expose-Headers': 'Location, Upload-Offset, Upload-Length, Tus-Resumable',
  'Tus-Resumable': '1.0.0',
}

export interface TusUpload {
  /** The four headers Bunny authorises an upload with, as the browser sent them. */
  headers: Record<string, string>
  /** Bytes received across PATCH requests. */
  bytes: number
}

export interface BunnyTusStub {
  /** Every upload the page started, in order. */
  uploads: TusUpload[]
  /** Lets held PATCH responses through (see `holdPatches`). */
  release(): void
}

/**
 * Serves Bunny's TUS endpoint for this page. With `holdPatches`, the data request is answered only
 * once `release()` is called, so the in-flight upload state (progress bar) can be asserted.
 * With `fail`, the upload is refused (HTTP 500), which tus-js-client reports after its retries.
 */
export async function stubBunnyTus(
  page: Page,
  options: { holdPatches?: boolean; fail?: boolean } = {},
): Promise<BunnyTusStub> {
  const uploads: TusUpload[] = []
  let releaseHeld: () => void = () => {}
  const held = new Promise<void>((resolve) => (releaseHeld = resolve))
  if (!options.holdPatches) releaseHeld()

  await page.route(`${TUS_ENDPOINT}**`, async (route: Route) => {
    const req = route.request()
    const method = req.method()
    if (method === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: CORS })
      return
    }
    if (options.fail) {
      await route.fulfill({ status: 500, headers: CORS, body: 'e2e: upload refused' })
      return
    }
    if (method === 'POST') {
      const headers = await req.allHeaders()
      const index = uploads.push({
        headers: {
          AuthorizationSignature: headers['authorizationsignature'] ?? '',
          AuthorizationExpire: headers['authorizationexpire'] ?? '',
          LibraryId: headers['libraryid'] ?? '',
          VideoId: headers['videoid'] ?? '',
        },
        bytes: 0,
      })
      await route.fulfill({
        status: 201,
        headers: { ...CORS, Location: `${TUS_ENDPOINT}/e2e-${index}` },
      })
      return
    }
    const index = Number(req.url().split('/e2e-').pop()) - 1
    const upload = uploads[index]
    if (method === 'HEAD') {
      await route.fulfill({
        status: 200,
        headers: {
          ...CORS,
          'Upload-Offset': String(upload?.bytes ?? 0),
          'Cache-Control': 'no-store',
        },
      })
      return
    }
    // PATCH: one chunk of bytes at Upload-Offset.
    const offset = Number((await req.allHeaders())['upload-offset'] ?? 0)
    const received = req.postDataBuffer()?.length ?? 0
    await held
    if (upload) upload.bytes = offset + received
    await route.fulfill({
      status: 204,
      headers: { ...CORS, 'Upload-Offset': String(offset + received) },
    })
  })

  return { uploads, release: () => releaseHeld() }
}
