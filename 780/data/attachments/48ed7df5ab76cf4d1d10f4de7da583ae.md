## GET http://localhost:36563/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Mon, 05 Oct 2026 07:50:51 GMT
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
curl 'http://localhost:36563/echo?id=1'
```
