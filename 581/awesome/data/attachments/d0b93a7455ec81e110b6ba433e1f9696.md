## PATCH http://localhost:42583/echo → 200 OK

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
date: Sun, 12 Apr 2026 09:37:30 GMT
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
curl 'http://localhost:42583/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
