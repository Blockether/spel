## GET http://localhost:41623/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Mon, 23 Nov 2026 10:48:49 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "id=1"
}
```

### cURL
```bash
curl 'http://localhost:41623/echo?id=1'
```
