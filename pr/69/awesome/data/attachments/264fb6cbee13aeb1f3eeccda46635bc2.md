## PUT http://localhost:40017/echo → 200 OK

### Request Headers
```
PUT http://localhost:40017/echo
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
date: Mon, 02 Mar 2026 08:12:59 GMT
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
curl 'http://localhost:40017/echo' \
  -X PUT \
  -d '{"name":"Eve Updated"}'
```
