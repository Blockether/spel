## PUT http://localhost:39733/echo → 200 OK

### Request Headers
```
PUT http://localhost:39733/echo
```

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Fri, 10 Apr 2026 08:22:07 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:39733/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
