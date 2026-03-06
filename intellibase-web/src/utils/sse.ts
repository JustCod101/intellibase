/**
 * Utility to read Server-Sent Events (SSE) using the Fetch API.
 * This allows passing custom headers like Authorization which native EventSource doesn't support.
 */

interface SSEOptions {
  onMessage: (data: any) => void;
  onError?: (error: Error) => void;
  onDone?: () => void;
  signal?: AbortSignal;
}

export const fetchSSE = async (url: string, options: SSEOptions) => {
  const token = localStorage.getItem('accessToken');
  
  try {
    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'Accept': 'text/event-stream',
        'Authorization': `Bearer ${token}`,
      },
      signal: options.signal
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const body = response.body;
    if (!body) throw new Error('ReadableStream not yet supported in this browser.');

    const reader = body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        options.onDone?.();
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || ''; // Keep the last incomplete line in the buffer

      let currentEvent = '';
      for (const line of lines) {
        if (line.trim() === '') continue; // Empty line separates events

        if (line.startsWith('event:')) {
          currentEvent = line.substring(6).trim();
        } else if (line.startsWith('data:')) {
          const dataContent = line.substring(5).trim();
          if (dataContent === '[DONE]') {
             options.onDone?.();
             return;
          }
          try {
            const parsedData = JSON.parse(dataContent);
            options.onMessage({ event: currentEvent, data: parsedData });
          } catch (e) {
            // Data might not be JSON, fallback to raw text if needed
            options.onMessage({ event: currentEvent, data: dataContent });
          }
          currentEvent = ''; // reset after data
        }
      }
    }
  } catch (err: any) {
    if (err.name === 'AbortError') {
      console.log('Fetch aborted');
    } else {
      options.onError?.(err);
    }
  }
};
