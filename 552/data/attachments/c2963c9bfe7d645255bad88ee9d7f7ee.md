## PATCH http://localhost:38717/echo → 200 OK

### Request Headers
```
PATCH http://localhost:38717/echo
```

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
date: Fri, 10 Apr 2026 22:29:03 GMT
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
curl 'http://localhost:38717/echo' \
  -X PATCH \
  -d '{
  "email": "eve@new.com"
}'
```
