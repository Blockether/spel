## PATCH http://localhost:45957/echo → 200 OK

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
date: Sun, 12 Apr 2026 19:26:31 GMT
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
curl 'http://localhost:45957/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
