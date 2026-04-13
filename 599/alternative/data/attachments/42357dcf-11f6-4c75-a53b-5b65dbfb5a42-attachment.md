## PATCH http://localhost:41553/echo → 200 OK

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
date: Mon, 13 Apr 2026 02:46:47 GMT
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
curl 'http://localhost:41553/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
