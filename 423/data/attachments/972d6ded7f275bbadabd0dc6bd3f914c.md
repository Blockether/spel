## DELETE http://localhost:37845/echo → 200 OK

### Request Headers
```
DELETE http://localhost:37845/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 06 Mar 2026 22:56:03 GMT
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
curl 'http://localhost:37845/echo' \
  -X DELETE
```
