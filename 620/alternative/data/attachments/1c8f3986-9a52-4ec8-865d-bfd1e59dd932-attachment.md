## PATCH http://localhost:44377/echo → 200 OK

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
date: Wed, 22 Apr 2026 17:22:53 GMT
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
curl 'http://localhost:44377/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
