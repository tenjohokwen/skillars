import { api } from 'src/boot/axios'
import * as tus from 'tus-js-client'

export const videoApi = {
  // signal is an AbortSignal from AbortController — passed by video.store.js for cancel-during-initiation support
  initiateUpload(payload, signal) {
    return api.post('/api/video/uploads/initiate', payload, { signal })
  },

  createTusUpload({
    file,
    signedUploadUrl,
    providerUploadId,
    tusAuthorizationSignature,
    tusAuthorizationExpire,
    tusLibraryId,
    onProgress,
    onSuccess,
    onError,
  }) {
    return new tus.Upload(file, {
      endpoint: signedUploadUrl,
      retryDelays: [1000, 3000, 5000, 10000, 20000], // no immediate retry — avoids hammering Bunny on 429/503
      // Bunny.net requires all four headers for TUS auth — computed server-side
      headers: {
        AuthorizationSignature: tusAuthorizationSignature,
        AuthorizationExpire: String(tusAuthorizationExpire),
        LibraryId: String(tusLibraryId),
        VideoId: providerUploadId,
      },
      // Bunny.net requires 'title' and 'filetype' in Upload-Metadata
      metadata: {
        title: file.name,
        filetype: file.type,
      },
      onProgress(bytesUploaded, bytesTotal) {
        onProgress?.(bytesUploaded, bytesTotal)
      },
      onSuccess,
      onError,
    })
  },
}
