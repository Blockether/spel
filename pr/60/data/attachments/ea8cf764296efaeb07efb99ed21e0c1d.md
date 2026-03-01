## DELETE http://localhost:39977/echo → 200 OK

### Request Headers
```
DELETE http://localhost:39977/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 01 Mar 2026 11:11:43 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:39977/echo' \
  -X DELETE
```
