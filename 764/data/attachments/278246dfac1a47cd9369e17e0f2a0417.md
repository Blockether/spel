## PATCH http://localhost:46689/echo → 200 OK

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
date: Sun, 27 Sep 2026 11:56:47 GMT
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
curl 'http://localhost:46689/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
