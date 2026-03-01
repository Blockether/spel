## GET http://localhost:46497/echo → 200 OK

### Request Headers
```
GET http://localhost:46497/echo
```

### Response Headers
```
content-length: 46
content-type: application/json
date: Sun, 01 Mar 2026 20:06:09 GMT
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
curl 'http://localhost:46497/echo'
```
