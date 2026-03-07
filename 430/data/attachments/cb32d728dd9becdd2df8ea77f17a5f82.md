## DELETE http://localhost:45259/echo → 200 OK

### Request Headers
```
DELETE http://localhost:45259/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 07 Mar 2026 11:07:35 GMT
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
curl 'http://localhost:45259/echo' \
  -X DELETE
```
