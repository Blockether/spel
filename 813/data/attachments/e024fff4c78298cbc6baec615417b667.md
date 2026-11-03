## PATCH http://localhost:37963/echo → 200 OK

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
date: Tue, 03 Nov 2026 17:51:39 GMT
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
curl 'http://localhost:37963/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
