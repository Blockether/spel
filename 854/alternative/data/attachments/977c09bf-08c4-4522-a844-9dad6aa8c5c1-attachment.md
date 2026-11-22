## PATCH http://localhost:41721/echo → 200 OK

### Request Body
```json
{
  "email": "eve@new.com"
}
```

### Response Headers
```
content-length: 64
content-type: application/json
date: Sun, 22 Nov 2026 17:48:06 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "eve@new.com"
  }
}
```

### cURL
```bash
curl 'http://localhost:41721/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
